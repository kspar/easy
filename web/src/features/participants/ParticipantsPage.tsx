import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Typography,
  CircularProgress,
  Alert,
  Box,
  Tab,
  Tabs,
  IconButton,
  Chip,
  Button,
  Menu,
  MenuItem,
  Snackbar,
  Tooltip,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  ListItemIcon,
  ListItemText,
  Accordion,
  AccordionSummary,
  AccordionDetails,
  Switch,
  TextField,
} from '@mui/material'
import {
  ArrowBackOutlined,
  AddOutlined,
  DeleteOutlined,
  RemoveOutlined,
  ContentCopyOutlined,
  LinkOutlined,
  SendOutlined,
  MoreVertOutlined,
  PersonAddOutlined,
  EditOutlined,
  FullscreenOutlined,
  CloseOutlined,
  ExpandMoreOutlined,
  ArrowDropDownOutlined,
  SyncOutlined,
} from '@mui/icons-material'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation, Trans } from 'react-i18next'
import { format, isPast } from 'date-fns'
import { et, enGB } from 'date-fns/locale'
import {
  type ColumnDef,
  type RowSelectionState,
  type SortingState,
  createColumnHelper,
} from '@tanstack/react-table'
import {
  useParticipants,
  useCourseGroups,
  useCourseInvite,
  useCreateInvite,
  useDeleteInvite,
  useRemoveStudents,
  useAddTeachers,
  useRemoveTeachers,
  useAddStudentsToGroup,
  useRemoveStudentFromGroup,
  useDeleteGroups,
  useSendMoodleInvites,
  useMoodleProps,
  useSyncMoodleStudents,
  useSyncMoodleGrades,
  useUpdateMoodleProps,
} from '../../api/exercises.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import useSavedGroup from '../../hooks/useSavedGroup.ts'
import DataTable from '../../components/DataTable.tsx'
import AddParticipantsDialog from './AddParticipantsDialog.tsx'
import CreateGroupDialog from './CreateGroupDialog.tsx'
import ConfirmDialog from './ConfirmDialog.tsx'
import EditInviteDialog from './EditInviteDialog.tsx'
import { useAuth } from '../../auth/AuthContext.tsx'
import type { TeacherParticipant, GroupResp } from '../../api/types.ts'
import logoSvg from '../../assets/logo.svg'

interface StudentRow {
  id: string
  name: string | null
  email: string | null
  groups: GroupResp[]
  isPending: boolean
  moodleUsername?: string
  inviteId?: string
}

interface ConfirmState {
  message: React.ReactNode
  label?: string
  color?: 'error' | 'primary' | 'warning'
  action: () => void
}

export default function ParticipantsPage() {
  const { activeRole } = useAuth()
  const isAdmin = activeRole === 'admin'
  const { courseId } = useParams<{ courseId: string }>()
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const [tab, setTab] = useState(0)
  const { data, isLoading, error } = useParticipants(courseId!)
  const { data: groups } = useCourseGroups(courseId!)
  const { data: invite, isLoading: inviteLoading } = useCourseInvite(courseId!)
  usePageTitle(t('participants.students'))

  const dateFnsLocale = i18n.language === 'et' ? et : enGB

  const createInvite = useCreateInvite(courseId!)
  const deleteInvite = useDeleteInvite(courseId!)
  const removeStudents = useRemoveStudents(courseId!)
  const addTeachers = useAddTeachers(courseId!)
  const removeTeachers = useRemoveTeachers(courseId!)
  const addToGroup = useAddStudentsToGroup(courseId!)
  const removeFromGroup = useRemoveStudentFromGroup(courseId!)
  const deleteGroups = useDeleteGroups(courseId!)
  const sendMoodleInvites = useSendMoodleInvites(courseId!)

  const isMoodleLinked = data?.moodle_linked ?? false

  // Moodle sync
  const { data: moodleData, refetch: refetchMoodle } = useMoodleProps(courseId!, isMoodleLinked)
  const [linkMoodleOpen, setLinkMoodleOpen] = useState(false)
  const moodleProps = moodleData?.moodle_props
  const syncStudents = useSyncMoodleStudents(courseId!)
  const syncGrades = useSyncMoodleGrades(courseId!)
  const updateMoodleProps = useUpdateMoodleProps(courseId!)
  const [editingMoodleShortName, setEditingMoodleShortName] = useState(false)
  const [moodleShortNameDraft, setMoodleShortNameDraft] = useState('')

  // Poll moodle sync status while in progress
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  useEffect(() => {
    const inProgress = moodleProps?.sync_students_in_progress || moodleProps?.sync_grades_in_progress
    if (inProgress && !pollRef.current) {
      pollRef.current = setInterval(() => refetchMoodle(), 3000)
    } else if (!inProgress && pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
    return () => {
      if (pollRef.current) clearInterval(pollRef.current)
    }
  }, [moodleProps?.sync_students_in_progress, moodleProps?.sync_grades_in_progress])

  // Snackbar
  const [snackMsg, setSnackMsg] = useState('')

  // Selection state
  const [studentSelection, setStudentSelection] = useState<RowSelectionState>({})
  const [teacherSelection, setTeacherSelection] = useState<RowSelectionState>({})
  const [groupSelection, setGroupSelection] = useState<RowSelectionState>({})

  // Sorting state
  const [studentSorting, setStudentSorting] = useState<SortingState>([])
  const [teacherSorting, setTeacherSorting] = useState<SortingState>([])
  const [groupSorting, setGroupSorting] = useState<SortingState>([])

  // Student filters
  const [filterGroup, setFilterGroup] = useSavedGroup(courseId!)
  const [filterStatus, setFilterStatus] = useState<string>('')
  const [filterGroupAnchor, setFilterGroupAnchor] = useState<HTMLElement | null>(null)
  const [filterStatusAnchor, setFilterStatusAnchor] = useState<HTMLElement | null>(null)

  // Derive selected ID arrays
  const selectedStudentIds = Object.keys(studentSelection).filter(k => studentSelection[k])
  const selectedTeacherIds = Object.keys(teacherSelection).filter(k => teacherSelection[k])
  const selectedGroupIds = Object.keys(groupSelection).filter(k => groupSelection[k])

  // Generic confirm dialog
  const [confirm, setConfirm] = useState<ConfirmState | null>(null)
  const confirmPending =
    removeStudents.isPending ||
    removeTeachers.isPending ||
    deleteGroups.isPending ||
    deleteInvite.isPending ||
    sendMoodleInvites.isPending ||
    updateMoodleProps.isPending

  // Invite dialogs
  const [inviteExpanded, setInviteExpanded] = useState(false)
  const [editInviteOpen, setEditInviteOpen] = useState(false)
  const [presentLinkOpen, setPresentLinkOpen] = useState(false)

  // Add teachers dialog
  const [addTeachersOpen, setAddTeachersOpen] = useState(false)

  // Create group dialog
  const [createGroupOpen, setCreateGroupOpen] = useState(false)

  // Group menu (shared for per-row and bulk add-to-group)
  const [groupMenuAnchor, setGroupMenuAnchor] = useState<HTMLElement | null>(
    null,
  )
  const [groupMenuStudentIds, setGroupMenuStudentIds] = useState<string[]>([])

  // Remove-from-group menu (bulk)
  const [removeGroupMenuAnchor, setRemoveGroupMenuAnchor] =
    useState<HTMLElement | null>(null)

  // Moodle pending student actions
  const [moodleMenuAnchor, setMoodleMenuAnchor] = useState<HTMLElement | null>(null)
  const [moodleMenuStudent, setMoodleMenuStudent] = useState<StudentRow | null>(null)
  const [enrolmentLinkStudent, setEnrolmentLinkStudent] = useState<StudentRow | null>(null)

  // Per-row action menus
  const [studentMenuAnchor, setStudentMenuAnchor] = useState<HTMLElement | null>(null)
  const [studentMenuId, setStudentMenuId] = useState<string | null>(null)
  const [teacherMenuAnchor, setTeacherMenuAnchor] = useState<HTMLElement | null>(null)
  const [teacherMenuId, setTeacherMenuId] = useState<string | null>(null)
  const [groupMenuRowAnchor, setGroupMenuRowAnchor] = useState<HTMLElement | null>(null)
  const [groupMenuRowId, setGroupMenuRowId] = useState<string | null>(null)

  // --- Build unified student rows ---

  const activeStudents = data?.students ?? []
  const moodlePending = data?.students_moodle_pending ?? []
  const teachers = data?.teachers ?? []

  const allStudents: StudentRow[] = useMemo(
    () => [
      ...activeStudents.map((s) => ({
        id: s.id,
        name: `${s.given_name} ${s.family_name}`,
        email: s.email,
        groups: s.groups,
        isPending: false,
      })),
      ...moodlePending.map((s) => ({
        id: `moodle:${s.moodle_username}`,
        name: null,
        email: s.email,
        groups: s.groups,
        isPending: true,
        moodleUsername: s.moodle_username,
        inviteId: s.invite_id,
      })),
    ],
    [activeStudents, moodlePending],
  )

  const filteredStudents = useMemo(() => {
    let result = allStudents
    if (filterGroup) {
      result = result.filter((s) => s.groups.some((g) => g.id === filterGroup))
    }
    if (filterStatus === 'active') {
      result = result.filter((s) => !s.isPending)
    } else if (filterStatus === 'pending') {
      result = result.filter((s) => s.isPending)
    }
    return result
  }, [allStudents, filterGroup, filterStatus])

  // --- Helpers to partition selected IDs ---

  function partitionSelectedIds(ids: string[]) {
    const active: string[] = []
    const moodleUsernames: string[] = []
    for (const id of ids) {
      if (id.startsWith('moodle:')) {
        moodleUsernames.push(id.slice('moodle:'.length))
      } else {
        active.push(id)
      }
    }
    return { active, moodleUsernames }
  }

  // --- Invite link handlers ---

  function handleCreateInvite() {
    const expiresAt = new Date()
    expiresAt.setMonth(expiresAt.getMonth() + 1)
    createInvite.mutate(
      { expires_at: expiresAt.toISOString(), allowed_uses: 50 },
      {
        onSuccess: () => {
          setInviteExpanded(true)
          setSnackMsg(t('participants.inviteLinkCreated'))
        },
      },
    )
  }

  function handleDeleteInvite() {
    setConfirm({
      message: t('participants.deleteInviteLinkConfirm'),
      label: t('general.delete'),
      action: () => {
        deleteInvite.mutate(undefined, {
          onSuccess: () => {
            setConfirm(null)
            setSnackMsg(t('participants.inviteLinkDeleted'))
          },
        })
      },
    })
  }

  function handleCopyInviteLink() {
    if (!invite) return
    const url = `${window.location.origin}/link/${invite.invite_id}`
    navigator.clipboard.writeText(url)
    setSnackMsg(t('general.copied'))
  }

  // --- Moodle invite handlers ---

  function handleShowEnrolmentLink() {
    setEnrolmentLinkStudent(moodleMenuStudent)
    setMoodleMenuAnchor(null)
  }

  function handleResendMoodleInvite() {
    const student = moodleMenuStudent
    setMoodleMenuAnchor(null)
    if (!student?.moodleUsername) return
    setConfirm({
      message: t('participants.resendInviteConfirm', { email: student.email }),
      label: t('participants.resendInvite'),
      color: 'primary',
      action: () => {
        sendMoodleInvites.mutate([student.moodleUsername!], {
          onSuccess: () => {
            setConfirm(null)
            setSnackMsg(t('participants.inviteSent'))
          },
        })
      },
    })
  }

  function handleBulkResendMoodleInvites() {
    if (selectedPendingMoodleUsernames.length === 0) return
    const isSingle = selectedPendingMoodleUsernames.length === 1
    const singleEmail = isSingle
      ? allStudents.find((s) => s.moodleUsername === selectedPendingMoodleUsernames[0])?.email
      : undefined
    setConfirm({
      message: isSingle
        ? t('participants.resendInviteConfirm', { email: singleEmail })
        : t('participants.resendInviteConfirmBulk', {
            count: selectedPendingMoodleUsernames.length,
          }),
      label: t('participants.resendInvite'),
      color: 'primary',
      action: () => {
        sendMoodleInvites.mutate(selectedPendingMoodleUsernames, {
          onSuccess: () => {
            setConfirm(null)
            setSnackMsg(t('participants.invitesSent', {
              count: selectedPendingMoodleUsernames.length,
            }))
          },
        })
      },
    })
  }

  function handleCopyEnrolmentLink() {
    if (!enrolmentLinkStudent?.inviteId) return
    const url = `${window.location.origin}/moodle/link/${enrolmentLinkStudent.inviteId}`
    navigator.clipboard.writeText(url)
    setSnackMsg(t('general.copied'))
  }

  // --- Action handlers ---

  function handleAddTeachers(emails: string[]) {
    addTeachers.mutate(
      emails.map((email) => ({ email })),
      {
        onSuccess: () => {
          setAddTeachersOpen(false)
          setSnackMsg(t('participants.teachersAdded'))
        },
      },
    )
  }

  function handleBulkRemoveStudents() {
    // Only remove active students — moodle pending are managed by Moodle sync
    const { active } = partitionSelectedIds(selectedStudentIds)
    if (active.length === 0) return
    const isSingle = active.length === 1
    setConfirm({
      message: isSingle ? (
        <Trans
          i18nKey="participants.removeStudentConfirm"
          values={{ name: studentName(active[0]) }}
          components={{ bold: <strong /> }}
        />
      ) : (
        t('participants.removeSelectedStudentsConfirm', {
          count: active.length,
        })
      ),
      action: () => {
        removeStudents.mutate(active, {
          onSuccess: () => {
            setConfirm(null)
            setStudentSelection({})
            setSnackMsg(
              isSingle
                ? t('participants.studentRemoved')
                : t('participants.studentsRemoved'),
            )
          },
        })
      },
    })
  }

  function handleBulkRemoveTeachers() {
    const ids = selectedTeacherIds
    const isSingle = ids.length === 1
    setConfirm({
      message: isSingle ? (
        <Trans
          i18nKey="participants.removeTeacherConfirm"
          values={{ name: teacherName(ids[0]) }}
          components={{ bold: <strong /> }}
        />
      ) : (
        t('participants.removeSelectedTeachersConfirm', {
          count: ids.length,
        })
      ),
      action: () => {
        removeTeachers.mutate(ids, {
          onSuccess: () => {
            setConfirm(null)
            setTeacherSelection({})
            setSnackMsg(
              isSingle
                ? t('participants.teacherRemoved')
                : t('participants.teachersRemoved'),
            )
          },
        })
      },
    })
  }

  function studentsInGroup(groupId: string): StudentRow[] {
    return allStudents.filter((s) => s.groups.some((g) => g.id === groupId))
  }

  function studentDisplayName(s: StudentRow): string {
    return s.name ?? s.email ?? s.moodleUsername ?? '?'
  }

  function handleDeleteGroups(ids: string[]) {
    const isSingle = ids.length === 1

    const affectedByGroup = ids.map((id) => ({
      groupId: id,
      name: groupName(id),
      students: studentsInGroup(id),
    }))
    const hasStudents = affectedByGroup.some((g) => g.students.length > 0)

    const message = isSingle ? (
      <>
        <Trans
          i18nKey={hasStudents ? 'participants.deleteGroupWithStudentsConfirm' : 'participants.deleteGroupConfirm'}
          values={{ name: affectedByGroup[0].name }}
          components={{ bold: <strong /> }}
        />
        {hasStudents && (
          <Box component="ul" sx={{ mt: 1, mb: 0, pl: 2.5 }}>
            {affectedByGroup[0].students.map((s) => (
              <li key={s.id}>{studentDisplayName(s)}</li>
            ))}
          </Box>
        )}
      </>
    ) : (
      <>
        {t(hasStudents ? 'participants.deleteSelectedGroupsWithStudentsConfirm' : 'participants.deleteSelectedGroupsConfirm', { count: ids.length })}
        {hasStudents && (
          <>
            {affectedByGroup.filter((g) => g.students.length > 0).map((g) => (
              <Box key={g.groupId} sx={{ mt: 1 }}>
                <strong>{g.name}</strong>
                <Box component="ul" sx={{ mt: 0.5, mb: 0, pl: 2.5 }}>
                  {g.students.map((s) => (
                    <li key={s.id}>{studentDisplayName(s)}</li>
                  ))}
                </Box>
              </Box>
            ))}
          </>
        )}
      </>
    )

    setConfirm({
      message,
      label: t('general.delete'),
      action: async () => {
        for (const g of affectedByGroup) {
          if (g.students.length === 0) continue
          const { active, moodleUsernames } = partitionSelectedIds(
            g.students.map((s) => s.id),
          )
          await removeFromGroup.mutateAsync({
            groupId: g.groupId,
            activeStudentIds: active,
            moodlePendingUsernames: moodleUsernames,
          })
        }
        deleteGroups.mutate(ids, {
          onSuccess: () => {
            setConfirm(null)
            setGroupSelection({})
            setSnackMsg(
              isSingle
                ? t('participants.groupDeleted')
                : t('participants.groupsDeleted'),
            )
          },
        })
      },
    })
  }

  function handleBulkDeleteGroups() {
    handleDeleteGroups(selectedGroupIds)
  }

  function handleRemoveStudent(id: string) {
    setConfirm({
      message: (
        <Trans
          i18nKey="participants.removeStudentConfirm"
          values={{ name: studentName(id) }}
          components={{ bold: <strong /> }}
        />
      ),
      action: () => {
        removeStudents.mutate([id], {
          onSuccess: () => {
            setConfirm(null)
            setSnackMsg(t('participants.studentRemoved'))
          },
        })
      },
    })
  }

  function handleRemoveTeacher(id: string) {
    setConfirm({
      message: (
        <Trans
          i18nKey="participants.removeTeacherConfirm"
          values={{ name: teacherName(id) }}
          components={{ bold: <strong /> }}
        />
      ),
      action: () => {
        removeTeachers.mutate([id], {
          onSuccess: () => {
            setConfirm(null)
            setSnackMsg(t('participants.teacherRemoved'))
          },
        })
      },
    })
  }

  function handleAddToGroup(groupId: string) {
    if (groupMenuStudentIds.length === 0) return
    const { active, moodleUsernames } = partitionSelectedIds(groupMenuStudentIds)
    addToGroup.mutate(
      { groupId, activeStudentIds: active, moodlePendingUsernames: moodleUsernames },
      {
        onSuccess: () => {
          setGroupMenuAnchor(null)
          setGroupMenuStudentIds([])
        },
      },
    )
  }

  function handleRemoveFromGroup(
    student: StudentRow,
    group: GroupResp,
  ) {
    const { active, moodleUsernames } = partitionSelectedIds([student.id])
    removeFromGroup.mutate({
      groupId: group.id,
      activeStudentIds: active,
      moodlePendingUsernames: moodleUsernames,
    })
  }

  function handleBulkRemoveFromGroup(groupId: string) {
    const { active, moodleUsernames } = partitionSelectedIds(selectedStudentIds)
    removeFromGroup.mutate(
      { groupId, activeStudentIds: active, moodlePendingUsernames: moodleUsernames },
      {
        onSuccess: () => setRemoveGroupMenuAnchor(null),
      },
    )
  }

  function openGroupMenu(anchor: HTMLElement, studentIds: string[]) {
    setGroupMenuAnchor(anchor)
    setGroupMenuStudentIds(studentIds)
  }

  // Name lookups for single-item confirm messages
  function studentName(id: string): string {
    const s = allStudents.find((s) => s.id === id)
    return s?.name ?? ''
  }

  function teacherName(id: string): string {
    const t = teachers.find((t) => t.id === id)
    return t ? `${t.given_name} ${t.family_name}` : ''
  }

  function groupName(id: string): string {
    return groups?.find((g) => g.id === id)?.name ?? ''
  }

  // Derive group student counts from participants data (active + moodle pending)
  function groupStudentCount(groupId: string): number {
    return allStudents.filter((s) =>
      s.groups.some((g) => g.id === groupId),
    ).length
  }

  // Can we remove any of the selected students? (only active students can be removed)
  const selectedHasActiveStudents = selectedStudentIds.some(
    (id) => !id.startsWith('moodle:'),
  )

  // Are any of the selected students moodle pending?
  const selectedPendingMoodleUsernames = selectedStudentIds
    .filter((id) => id.startsWith('moodle:'))
    .map((id) => id.slice('moodle:'.length))

  // Groups that any of the selected students belong to
  const selectedStudentGroups =
    groups?.filter((g) =>
      allStudents.some(
        (s) =>
          studentSelection[s.id] && s.groups.some((sg) => sg.id === g.id),
      ),
    ) ?? []

  // --- Column definitions ---

  const studentColumnHelper = createColumnHelper<StudentRow>()
  const studentColumns = useMemo(
    () => {
      const cols: ColumnDef<StudentRow, any>[] = [
        studentColumnHelper.accessor('name', {
          header: t('general.name'),
          cell: ({ row }) => {
            const s = row.original
            if (s.isPending) {
              return (
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <Chip
                    label={t('participants.pending')}
                    size="small"
                    color="warning"
                    variant="outlined"
                  />
                  {s.moodleUsername && (
                    <Typography variant="caption" color="text.secondary">
                      {s.moodleUsername}
                    </Typography>
                  )}
                </Box>
              )
            }
            return s.name
          },
          sortingFn: (a, b) => {
            const aName = a.original.name ?? ''
            const bName = b.original.name ?? ''
            return aName.localeCompare(bName)
          },
        }),
        studentColumnHelper.accessor('email', { header: t('general.email') }),
        studentColumnHelper.display({
          id: 'groups',
          header: t('participants.groups'),
          enableSorting: false,
          cell: ({ row }) => (
            <Box
              sx={{
                display: 'flex',
                gap: 0.5,
                flexWrap: 'wrap',
                alignItems: 'center',
              }}
            >
              {row.original.groups.map((g) => (
                <Chip
                  key={g.id}
                  label={g.name}
                  size="small"
                  variant="outlined"
                  onDelete={isMoodleLinked ? undefined : () => handleRemoveFromGroup(row.original, g)}
                />
              ))}
              {!isMoodleLinked && groups && groups.length > 0 && (
                <Chip
                  icon={<AddOutlined />}
                  label={t('participants.addToGroup')}
                  size="small"
                  variant="outlined"
                  onClick={(e) =>
                    openGroupMenu(e.currentTarget, [row.original.id])
                  }
                />
              )}
            </Box>
          ),
        }),
      ]

      cols.push(
        studentColumnHelper.display({
          id: 'actions',
          header: '',
          enableSorting: false,
          cell: ({ row }) => {
            const s = row.original
            if (isMoodleLinked && s.isPending && s.inviteId) {
              return (
                <IconButton
                  size="small"
                  onClick={(e) => {
                    setMoodleMenuAnchor(e.currentTarget)
                    setMoodleMenuStudent(s)
                  }}
                >
                  <MoreVertOutlined fontSize="small" />
                </IconButton>
              )
            }
            if (!s.isPending) {
              return (
                <IconButton
                  size="small"
                  onClick={(e) => {
                    setStudentMenuAnchor(e.currentTarget)
                    setStudentMenuId(s.id)
                  }}
                >
                  <MoreVertOutlined fontSize="small" />
                </IconButton>
              )
            }
            return null
          },
        }),
      )

      return cols
    },
    [t, groups, isMoodleLinked],
  )

  const teacherColumnHelper = createColumnHelper<TeacherParticipant>()
  const teacherColumns = useMemo(
    () => [
      teacherColumnHelper.accessor(
        (t) => `${t.given_name} ${t.family_name}`,
        { id: 'name', header: t('general.name') },
      ),
      teacherColumnHelper.accessor('email', { header: t('general.email') }),
      teacherColumnHelper.display({
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <IconButton
            size="small"
            onClick={(e) => {
              setTeacherMenuAnchor(e.currentTarget)
              setTeacherMenuId(row.original.id)
            }}
          >
            <MoreVertOutlined fontSize="small" />
          </IconButton>
        ),
      }),
    ] as ColumnDef<TeacherParticipant, any>[],
    [t],
  )

  interface GroupWithCount extends GroupResp {
    studentCount: number
  }

  const groupsWithCounts: GroupWithCount[] = useMemo(
    () => (groups ?? []).map((g) => ({ ...g, studentCount: groupStudentCount(g.id) })),
    [groups, data?.students, data?.students_moodle_pending],
  )

  const groupColumnHelper = createColumnHelper<GroupWithCount>()
  const groupColumns = useMemo(
    () => [
      groupColumnHelper.accessor('name', { header: t('general.name') }),
      groupColumnHelper.accessor('studentCount', {
        header: t('participants.students'),
      }),
      groupColumnHelper.display({
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <IconButton
            size="small"
            onClick={(e) => {
              setGroupMenuRowAnchor(e.currentTarget)
              setGroupMenuRowId(row.original.id)
            }}
          >
            <MoreVertOutlined fontSize="small" />
          </IconButton>
        ),
      }),
    ] as ColumnDef<GroupWithCount, any>[],
    [t],
  )

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
        <IconButton
          onClick={() => navigate(`/courses/${courseId}/exercises`)}
          size="small"
        >
          <ArrowBackOutlined />
        </IconButton>
        <Typography variant="h5">{t('participants.title')}</Typography>
      </Box>

      {isLoading && <CircularProgress />}
      {error && (
        <Alert severity="error">{t('general.somethingWentWrong')}</Alert>
      )}

      {data && (
        <>
          <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
            <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ flexGrow: 1 }}>
              <Tab
                label={`${t('participants.students')} (${allStudents.length})`}
              />
              <Tab
                label={`${t('participants.teachers')} (${teachers.length})`}
              />
              <Tab
                label={`${t('participants.groups')} (${groups?.length ?? 0})`}
              />
              {isMoodleLinked && <Tab label={t('participants.moodle')} />}
            </Tabs>
            {isAdmin && !isMoodleLinked && (
              <Button
                size="small"
                onClick={() => {
                  setMoodleShortNameDraft('')
                  setLinkMoodleOpen(true)
                }}
              >
                {t('participants.linkMoodle')}
              </Button>
            )}
          </Box>

          {/* ===== Students tab ===== */}
          {tab === 0 && (
            <>
              {/* Invite link section (hidden for Moodle-synced courses, replaced by selection toolbar) */}
              {selectedStudentIds.length > 0 ? (
                <SelectionToolbar
                  count={selectedStudentIds.length}
                  t={t}
                  actions={
                    <>
                      {!isMoodleLinked && groups && groups.length > 0 && (
                        <Button
                          size="small"
                          startIcon={<AddOutlined />}
                          onClick={(e) =>
                            openGroupMenu(e.currentTarget, selectedStudentIds)
                          }
                        >
                          {t('participants.addToGroup')}
                        </Button>
                      )}
                      {!isMoodleLinked && selectedStudentGroups.length > 0 && (
                        <Button
                          size="small"
                          color="warning"
                          startIcon={<RemoveOutlined />}
                          onClick={(e) =>
                            setRemoveGroupMenuAnchor(e.currentTarget)
                          }
                        >
                          {t('participants.removeFromGroup')}
                        </Button>
                      )}
                      {isMoodleLinked && selectedPendingMoodleUsernames.length > 0 && (
                        <Button
                          size="small"
                          startIcon={<SendOutlined />}
                          onClick={handleBulkResendMoodleInvites}
                        >
                          {t('participants.resendInvite')}
                        </Button>
                      )}
                      {selectedHasActiveStudents && (
                        <Button
                          size="small"
                          color="error"
                          startIcon={<DeleteOutlined />}
                          onClick={handleBulkRemoveStudents}
                        >
                          {t('participants.removeFromCourse')}
                        </Button>
                      )}
                    </>
                  }
                />
              ) : !data.moodle_linked && !inviteLoading ? (
                invite ? (
                  <Accordion
                    disableGutters
                    variant="outlined"
                    expanded={inviteExpanded}
                    onChange={(_, expanded) => setInviteExpanded(expanded)}
                    sx={{ mb: 2, '&::before': { display: 'none' } }}
                  >
                    <AccordionSummary expandIcon={<ExpandMoreOutlined />} sx={{ minHeight: 0, '&.Mui-expanded': { minHeight: 0 }, '& .MuiAccordionSummary-content': { my: 1.25 }, '& .MuiAccordionSummary-content.Mui-expanded': { my: 1.25 } }}>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                        <PersonAddOutlined fontSize="small" color="action" />
                        <Typography variant="body2">
                          {t('participants.inviteLink')}
                        </Typography>
                      </Box>
                    </AccordionSummary>
                    <AccordionDetails sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                        <Typography
                          variant="body2"
                          sx={{
                            fontFamily: 'monospace',
                            bgcolor: 'action.hover',
                            px: 1,
                            py: 0.5,
                            borderRadius: 1,
                            userSelect: 'all',
                          }}
                        >
                          {`${window.location.origin}/link/${invite.invite_id}`}
                        </Typography>
                        <Tooltip title={t('general.copy')}>
                          <IconButton size="small" onClick={handleCopyInviteLink}>
                            <ContentCopyOutlined fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </Box>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                        <Typography variant="body2" color="text.secondary">
                          {t('participants.inviteUsage', {
                            used: invite.used_count,
                            max: invite.allowed_uses,
                          })}
                        </Typography>
                        <Typography variant="body2" color="text.secondary">·</Typography>
                        {isPast(new Date(invite.expires_at)) ? (
                          <Typography variant="body2" color="error">
                            {t('participants.inviteExpired')}
                          </Typography>
                        ) : (
                          <Typography variant="body2" color="text.secondary">
                            {t('participants.inviteExpiry')}: {format(new Date(invite.expires_at), 'PPp', {
                              locale: dateFnsLocale,
                            })}
                          </Typography>
                        )}
                      </Box>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                        <Button
                          size="small"
                          variant="outlined"
                          startIcon={<FullscreenOutlined />}
                          onClick={() => setPresentLinkOpen(true)}
                        >
                          {t('participants.presentLink')}
                        </Button>
                        <Tooltip title={t('general.edit')}>
                          <IconButton
                            size="small"
                            onClick={() => setEditInviteOpen(true)}
                          >
                            <EditOutlined fontSize="small" />
                          </IconButton>
                        </Tooltip>
                        <Tooltip title={t('participants.deleteInviteLink')}>
                          <IconButton
                            size="small"
                            onClick={handleDeleteInvite}
                            color="error"
                          >
                            <DeleteOutlined fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </Box>
                    </AccordionDetails>
                  </Accordion>
                ) : (
                  <Box sx={{ mb: 2 }}>
                    <Button
                      variant="outlined"
                      startIcon={<LinkOutlined />}
                      onClick={handleCreateInvite}
                      disabled={createInvite.isPending}
                    >
                      {t('participants.createInviteLink')}
                    </Button>
                  </Box>
                )
              ) : null}

              {(groups && groups.length > 0 || allStudents.some((s) => s.isPending)) && (
                <Box sx={{ display: 'flex', gap: 0.75, mb: 2, flexWrap: 'wrap', alignItems: 'center' }}>
                  {groups && groups.length > 0 && (
                    <>
                      <Chip
                        label={filterGroup
                          ? groups.find((g) => g.id === filterGroup)?.name
                          : t('participants.allGroups')
                        }
                        deleteIcon={<ArrowDropDownOutlined />}
                        onDelete={(e) => setFilterGroupAnchor(e.currentTarget.closest('div'))}
                        onClick={(e) => setFilterGroupAnchor(e.currentTarget)}
                        size="small"
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
                          onClick={() => {
                            setFilterGroup('')
                            setStudentSelection({})
                            setFilterGroupAnchor(null)
                          }}
                        >
                          {t('participants.allGroups')}
                        </MenuItem>
                        {groups.map((g) => (
                          <MenuItem
                            key={g.id}
                            selected={filterGroup === g.id}
                            onClick={() => {
                              setFilterGroup(g.id)
                              setStudentSelection({})
                              setFilterGroupAnchor(null)
                            }}
                          >
                            {g.name}
                          </MenuItem>
                        ))}
                      </Menu>
                    </>
                  )}
                  {allStudents.some((s) => s.isPending) && (
                    <>
                      <Chip
                        label={filterStatus
                          ? filterStatus === 'active' ? t('participants.active') : t('participants.pending')
                          : t('participants.allStatuses')
                        }
                        deleteIcon={<ArrowDropDownOutlined />}
                        onDelete={(e) => setFilterStatusAnchor(e.currentTarget.closest('div'))}
                        onClick={(e) => setFilterStatusAnchor(e.currentTarget)}
                        size="small"
                        variant={filterStatus ? 'filled' : 'outlined'}
                        color={filterStatus ? 'primary' : 'default'}
                      />
                      <Menu
                        anchorEl={filterStatusAnchor}
                        open={!!filterStatusAnchor}
                        onClose={() => setFilterStatusAnchor(null)}
                      >
                        <MenuItem
                          selected={!filterStatus}
                          onClick={() => {
                            setFilterStatus('')
                            setStudentSelection({})
                            setFilterStatusAnchor(null)
                          }}
                        >
                          {t('participants.allStatuses')}
                        </MenuItem>
                        <MenuItem
                          selected={filterStatus === 'active'}
                          onClick={() => {
                            setFilterStatus('active')
                            setStudentSelection({})
                            setFilterStatusAnchor(null)
                          }}
                        >
                          {t('participants.active')}
                        </MenuItem>
                        <MenuItem
                          selected={filterStatus === 'pending'}
                          onClick={() => {
                            setFilterStatus('pending')
                            setStudentSelection({})
                            setFilterStatusAnchor(null)
                          }}
                        >
                          {t('participants.pending')}
                        </MenuItem>
                      </Menu>
                    </>
                  )}
                </Box>
              )}

              <DataTable
                columns={studentColumns}
                data={filteredStudents}
                getRowId={(s) => s.id}
                rowSelection={studentSelection}
                onRowSelectionChange={setStudentSelection}
                sorting={studentSorting}
                onSortingChange={setStudentSorting}
              />
            </>
          )}

          {/* ===== Teachers tab ===== */}
          {tab === 1 && (
            <>
              {selectedTeacherIds.length > 0 ? (
                <SelectionToolbar
                  count={selectedTeacherIds.length}
                  t={t}
                  actions={
                    <Button
                      size="small"
                      color="error"
                      startIcon={<DeleteOutlined />}
                      onClick={handleBulkRemoveTeachers}
                    >
                      {t('participants.removeFromCourse')}
                    </Button>
                  }
                />
              ) : (
                <Box sx={{ mb: 2 }}>
                  <Button
                    variant="outlined"
                    startIcon={<AddOutlined />}
                    onClick={() => setAddTeachersOpen(true)}
                  >
                    {t('participants.addTeachers')}
                  </Button>
                </Box>
              )}

              <DataTable
                columns={teacherColumns}
                data={teachers}
                getRowId={(t) => t.id}
                rowSelection={teacherSelection}
                onRowSelectionChange={setTeacherSelection}
                sorting={teacherSorting}
                onSortingChange={setTeacherSorting}
              />
            </>
          )}

          {/* ===== Groups tab ===== */}
          {tab === 2 && (
            <>
              {selectedGroupIds.length > 0 ? (
                <SelectionToolbar
                  count={selectedGroupIds.length}
                  t={t}
                  actions={
                    <Button
                      size="small"
                      color="error"
                      startIcon={<DeleteOutlined />}
                      onClick={handleBulkDeleteGroups}
                    >
                      {t('general.delete')}
                    </Button>
                  }
                />
              ) : (
                <Box sx={{ mb: 2 }}>
                  <Button
                    variant="outlined"
                    startIcon={<AddOutlined />}
                    onClick={() => setCreateGroupOpen(true)}
                  >
                    {t('participants.createGroup')}
                  </Button>
                </Box>
              )}

              {groupsWithCounts.length > 0 ? (
                <DataTable
                  columns={groupColumns}
                  data={groupsWithCounts}
                  getRowId={(g) => g.id}
                  rowSelection={groupSelection}
                  onRowSelectionChange={setGroupSelection}
                  sorting={groupSorting}
                  onSortingChange={setGroupSorting}
                />
              ) : (
                <Typography color="text.secondary">
                  {t('participants.noGroups')}
                </Typography>
              )}
            </>
          )}

          {/* ===== Moodle tab ===== */}
          {isMoodleLinked && tab === 3 && moodleProps && (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <Typography variant="body2" color="text.secondary">
                  {t('participants.moodleShortName')}:
                </Typography>
                {editingMoodleShortName ? (
                  <TextField
                    size="small"
                    value={moodleShortNameDraft}
                    onChange={(e) => setMoodleShortNameDraft(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        updateMoodleProps.mutate({
                          moodle_props: {
                            moodle_short_name: moodleShortNameDraft,
                            sync_students: moodleProps.students_synced,
                            sync_grades: moodleProps.grades_synced,
                          },
                        }, {
                          onSuccess: () => {
                            setEditingMoodleShortName(false)
                            setSnackMsg(t('general.saved'))
                          },
                        })
                      } else if (e.key === 'Escape') {
                        setEditingMoodleShortName(false)
                      }
                    }}
                    slotProps={{ htmlInput: { style: { padding: '4px 8px' } } }}
                    autoFocus
                    sx={{ width: 250 }}
                  />
                ) : (
                  <Typography
                    variant="body2"
                    sx={{
                      fontFamily: 'monospace',
                      fontWeight: 600,
                      ...(isAdmin && {
                        cursor: 'pointer',
                        '&:hover': { textDecoration: 'underline' },
                      }),
                    }}
                    onClick={isAdmin ? () => {
                      setMoodleShortNameDraft(moodleProps.moodle_short_name)
                      setEditingMoodleShortName(true)
                    } : undefined}
                  >
                    {moodleProps.moodle_short_name}
                  </Typography>
                )}
              </Box>

              <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                {[
                  {
                    key: 'students' as const,
                    title: t('participants.syncStudents'),
                    help: t('participants.studentsSyncHelp'),
                    enabled: moodleProps.students_synced,
                    inProgress: syncStudents.isPending || moodleProps.sync_students_in_progress,
                    onSync: () => syncStudents.mutate(undefined, {
                      onSuccess: () => setSnackMsg(t('participants.studentsSyncDone')),
                    }),
                  },
                  {
                    key: 'grades' as const,
                    title: t('participants.syncGrades'),
                    help: t('participants.gradesSyncHelp'),
                    enabled: moodleProps.grades_synced,
                    inProgress: syncGrades.isPending || moodleProps.sync_grades_in_progress,
                    onSync: () => syncGrades.mutate(undefined, {
                      onSuccess: () => setSnackMsg(t('participants.gradesSyncDone')),
                    }),
                  },
                ].map((card) => (
                  <Box
                    key={card.key}
                    sx={{
                      flex: '1 1 300px',
                      border: 1,
                      borderColor: 'divider',
                      borderRadius: 1,
                      p: 2,
                      display: 'flex',
                      flexDirection: 'column',
                      gap: 1.5,
                    }}
                  >
                    <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                      <Typography variant="subtitle2">{card.title}</Typography>
                      {isAdmin ? (
                        <Switch
                          size="small"
                          checked={card.enabled}
                          disabled={updateMoodleProps.isPending}
                          onChange={(_, checked) => {
                            updateMoodleProps.mutate({
                              moodle_props: {
                                moodle_short_name: moodleProps.moodle_short_name,
                                sync_students: card.key === 'students' ? checked : moodleProps.students_synced,
                                sync_grades: card.key === 'grades' ? checked : moodleProps.grades_synced,
                              },
                            }, {
                              onSuccess: () => setSnackMsg(t('general.saved')),
                            })
                          }}
                        />
                      ) : (
                        <Chip
                          label={t(card.enabled ? 'general.enabled' : 'general.disabled')}
                          size="small"
                          color={card.enabled ? 'success' : 'default'}
                          variant="outlined"
                        />
                      )}
                    </Box>
                    <Typography variant="body2" color="text.secondary">
                      {card.help}
                    </Typography>
                    <Box>
                      <Button
                        variant="outlined"
                        size="small"
                        startIcon={<SyncOutlined />}
                        disabled={!card.enabled || card.inProgress}
                        onClick={card.onSync}
                      >
                        {card.inProgress ? t('participants.syncing') : card.title}
                      </Button>
                    </Box>
                  </Box>
                ))}
              </Box>

              {!isAdmin && (
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                  {t('participants.moodleAdminNote')}
                </Typography>
              )}

              {isAdmin && (
                <Box sx={{ mt: 2 }}>
                  <Button
                    size="small"
                    variant="outlined"
                    color="error"
                    onClick={() => {
                      setConfirm({
                        message: t('participants.unlinkMoodleConfirm'),
                        label: t('participants.unlinkMoodle'),
                        action: () => {
                          updateMoodleProps.mutate({ moodle_props: null }, {
                            onSuccess: () => {
                              setConfirm(null)
                              setTab(0)
                              setSnackMsg(t('participants.moodleUnlinked'))
                            },
                          })
                        },
                      })
                    }}
                  >
                    {t('participants.unlinkMoodle')}
                  </Button>
                </Box>
              )}
            </Box>
          )}
        </>
      )}

      {/* Link to Moodle dialog */}
      <Dialog
        open={linkMoodleOpen}
        onClose={() => setLinkMoodleOpen(false)}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle>{t('participants.linkMoodle')}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
          <Typography variant="body2" color="text.secondary">
            {t('participants.moodleNotLinked')}
          </Typography>
          <TextField
            label={t('participants.moodleShortName')}
            size="small"
            value={moodleShortNameDraft}
            onChange={(e) => setMoodleShortNameDraft(e.target.value)}
            autoFocus
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setLinkMoodleOpen(false)}>{t('general.cancel')}</Button>
          <Button
            variant="contained"
            disabled={!moodleShortNameDraft.trim() || updateMoodleProps.isPending}
            onClick={() => {
              updateMoodleProps.mutate({
                moodle_props: {
                  moodle_short_name: moodleShortNameDraft.trim(),
                  sync_students: false,
                  sync_grades: false,
                },
              }, {
                onSuccess: () => {
                  setLinkMoodleOpen(false)
                  setMoodleShortNameDraft('')
                  setSnackMsg(t('general.saved'))
                },
              })
            }}
          >
            {t('participants.linkMoodle')}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Group menu for adding students to group */}
      <Menu
        anchorEl={groupMenuAnchor}
        open={!!groupMenuAnchor}
        onClose={() => {
          setGroupMenuAnchor(null)
          setGroupMenuStudentIds([])
        }}
      >
        {groups
          ?.filter((g) => {
            // For single student, filter out groups they're already in
            if (groupMenuStudentIds.length === 1) {
              const student = allStudents.find(
                (s) => s.id === groupMenuStudentIds[0],
              )
              return !student?.groups.some((sg) => sg.id === g.id)
            }
            return true
          })
          .map((g) => (
            <MenuItem key={g.id} onClick={() => handleAddToGroup(g.id)}>
              {g.name}
            </MenuItem>
          ))}
      </Menu>

      {/* Group menu for removing selected students from group */}
      <Menu
        anchorEl={removeGroupMenuAnchor}
        open={!!removeGroupMenuAnchor}
        onClose={() => setRemoveGroupMenuAnchor(null)}
      >
        {selectedStudentGroups.map((g) => (
          <MenuItem
            key={g.id}
            onClick={() => handleBulkRemoveFromGroup(g.id)}
          >
            {g.name}
          </MenuItem>
        ))}
      </Menu>

      {/* Student row actions menu */}
      <Menu
        anchorEl={studentMenuAnchor}
        open={!!studentMenuAnchor}
        onClose={() => {
          setStudentMenuAnchor(null)
          setStudentMenuId(null)
        }}
      >
        <MenuItem
          onClick={() => {
            const id = studentMenuId
            setStudentMenuAnchor(null)
            setStudentMenuId(null)
            if (id) handleRemoveStudent(id)
          }}
        >
          <ListItemIcon><DeleteOutlined fontSize="small" color="error" /></ListItemIcon>
          <ListItemText>{t('participants.removeFromCourse')}</ListItemText>
        </MenuItem>
      </Menu>

      {/* Moodle pending student actions menu */}
      <Menu
        anchorEl={moodleMenuAnchor}
        open={!!moodleMenuAnchor}
        onClose={() => {
          setMoodleMenuAnchor(null)
          setMoodleMenuStudent(null)
        }}
      >
        <MenuItem onClick={handleShowEnrolmentLink}>
          <ListItemIcon><LinkOutlined fontSize="small" /></ListItemIcon>
          <ListItemText>{t('participants.showEnrolmentLink')}</ListItemText>
        </MenuItem>
        <MenuItem onClick={handleResendMoodleInvite}>
          <ListItemIcon><SendOutlined fontSize="small" /></ListItemIcon>
          <ListItemText>{t('participants.resendInvite')}</ListItemText>
        </MenuItem>
      </Menu>

      {/* Teacher row actions menu */}
      <Menu
        anchorEl={teacherMenuAnchor}
        open={!!teacherMenuAnchor}
        onClose={() => {
          setTeacherMenuAnchor(null)
          setTeacherMenuId(null)
        }}
      >
        <MenuItem
          onClick={() => {
            const id = teacherMenuId
            setTeacherMenuAnchor(null)
            setTeacherMenuId(null)
            if (id) handleRemoveTeacher(id)
          }}
        >
          <ListItemIcon><DeleteOutlined fontSize="small" color="error" /></ListItemIcon>
          <ListItemText>{t('participants.removeFromCourse')}</ListItemText>
        </MenuItem>
      </Menu>

      {/* Group row actions menu */}
      <Menu
        anchorEl={groupMenuRowAnchor}
        open={!!groupMenuRowAnchor}
        onClose={() => {
          setGroupMenuRowAnchor(null)
          setGroupMenuRowId(null)
        }}
      >
        <MenuItem
          onClick={() => {
            const id = groupMenuRowId
            setGroupMenuRowAnchor(null)
            setGroupMenuRowId(null)
            if (id) handleDeleteGroups([id])
          }}
        >
          <ListItemIcon><DeleteOutlined fontSize="small" color="error" /></ListItemIcon>
          <ListItemText>{t('general.delete')}</ListItemText>
        </MenuItem>
      </Menu>

      {/* Enrolment link modal */}
      <Dialog
        open={!!enrolmentLinkStudent}
        onClose={() => setEnrolmentLinkStudent(null)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>{t('participants.enrolmentLink')}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Typography variant="body2" color="text.secondary">
            {t('participants.enrolmentLinkHelp')}
          </Typography>
          <Box>
            <Typography variant="body2">
              {t('general.email')}: <strong>{enrolmentLinkStudent?.email}</strong>
            </Typography>
            <Typography variant="body2">
              {t('participants.moodleUsername')}: <strong>{enrolmentLinkStudent?.moodleUsername}</strong>
            </Typography>
          </Box>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              mt: 1,
            }}
          >
            <Typography
              variant="body2"
              sx={{
                fontFamily: 'monospace',
                bgcolor: 'action.hover',
                px: 1.5,
                py: 1,
                borderRadius: 1,
                userSelect: 'all',
                flexGrow: 1,
                wordBreak: 'break-all',
              }}
            >
              {enrolmentLinkStudent?.inviteId &&
                `${window.location.origin}/moodle/link/${enrolmentLinkStudent.inviteId}`}
            </Typography>
            <Tooltip title={t('general.copy')}>
              <IconButton size="small" onClick={handleCopyEnrolmentLink}>
                <ContentCopyOutlined fontSize="small" />
              </IconButton>
            </Tooltip>
          </Box>
        </DialogContent>
      </Dialog>

      {/* Dialogs */}
      {invite && (
        <>
          <EditInviteDialog
            courseId={courseId!}
            invite={invite}
            open={editInviteOpen}
            onClose={() => setEditInviteOpen(false)}
            onSuccess={setSnackMsg}
          />
          <Dialog
            open={presentLinkOpen}
            onClose={() => setPresentLinkOpen(false)}
            fullScreen
            slotProps={{
              paper: {
                sx: {
                  bgcolor: (theme) =>
                    theme.palette.mode === 'dark'
                      ? '#000000'
                      : 'background.default',
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 6,
                  cursor: 'pointer',
                  userSelect: 'none',
                  p: 6,
                },
                onClick: () => setPresentLinkOpen(false),
              },
            }}
          >
            <IconButton
              onClick={() => setPresentLinkOpen(false)}
              sx={{ position: 'absolute', top: 24, right: 24 }}
            >
              <CloseOutlined />
            </IconButton>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
              <Box
                component="img"
                src={logoSvg}
                alt=""
                sx={{
                  width: 48,
                  height: 48,
                  filter: (theme) =>
                    theme.palette.mode === 'light'
                      ? 'invert(42%) sepia(52%) saturate(600%) hue-rotate(84deg) brightness(92%)'
                      : 'invert(70%) sepia(30%) saturate(500%) hue-rotate(84deg) brightness(95%)',
                }}
              />
              <Typography
                sx={{
                  fontFamily: "'Sniglet', cursive",
                  fontSize: 'clamp(1.5rem, 4vw, 2.5rem)',
                  color: 'primary.main',
                  letterSpacing: '0.01em',
                }}
              >
                LAHENDUS
              </Typography>
            </Box>
            <Typography
              sx={{
                fontFamily: 'monospace',
                fontSize: 'clamp(1rem, 4.5vw, 4rem)',
                textAlign: 'center',
                userSelect: 'text',
                whiteSpace: 'nowrap',
              }}
            >
              {`${window.location.origin.replace(/^https?:\/\//, '')}/link/${invite.invite_id}`}
            </Typography>
          </Dialog>
        </>
      )}

      <AddParticipantsDialog
        open={addTeachersOpen}
        title={t('participants.addTeachers')}
        isPending={addTeachers.isPending}
        onClose={() => setAddTeachersOpen(false)}
        onSubmit={handleAddTeachers}
      />

      <CreateGroupDialog
        courseId={courseId!}
        open={createGroupOpen}
        onClose={() => setCreateGroupOpen(false)}
        onSuccess={setSnackMsg}
      />

      <ConfirmDialog
        open={!!confirm}
        message={confirm?.message ?? ''}
        confirmLabel={confirm?.label}
        confirmColor={confirm?.color}
        isPending={confirmPending}
        onClose={() => setConfirm(null)}
        onConfirm={() => confirm?.action()}
      />

      <Snackbar
        open={!!snackMsg}
        autoHideDuration={3000}
        onClose={() => setSnackMsg('')}
        message={snackMsg}
      />
    </>
  )
}

function SelectionToolbar({
  count,
  t,
  actions,
}: {
  count: number
  t: (key: string, opts?: Record<string, unknown>) => string
  actions: React.ReactNode
}) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        mb: 2,
        px: 1.5,
        py: 0.75,
        bgcolor: 'action.selected',
        borderRadius: 1,
      }}
    >
      <Typography variant="body2" sx={{ mr: 1 }}>
        {t('participants.selected', { count })}
      </Typography>
      {actions}
    </Box>
  )
}
