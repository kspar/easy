import { useEffect, useState } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
  Divider,
  Snackbar,
  Typography,
  Box,
  IconButton,
  Autocomplete,
  Accordion,
  AccordionSummary,
  AccordionDetails,
  Select,
  MenuItem,
  InputLabel,
  FormControl,
} from '@mui/material'
import {
  DeleteOutlined,
  ExpandMoreOutlined,
} from '@mui/icons-material'
import { DateTimePicker } from '@mui/x-date-pickers/DateTimePicker'
import { useTranslation } from 'react-i18next'
import {
  useUpdateCourseExercise,
  useParticipants,
  useCourseGroups,
  usePutExerciseExceptions,
  useDeleteExerciseExceptions,
} from '../../api/exercises.ts'
import type {
  TeacherExerciseDetails,
  ExceptionStudent,
  ExceptionGroup,
  StudentParticipant,
  GroupResp,
} from '../../api/types.ts'

type VisibilityMode = 'visible' | 'hidden' | 'scheduled'
// Exception visibility adds a "no override" option
type ExceptionVisibility = 'default' | VisibilityMode

function parseIso(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const d = new Date(iso)
  return isNaN(d.getTime()) ? null : d
}

function getVisibilityMode(exercise: TeacherExerciseDetails): VisibilityMode {
  if (!exercise.student_visible && !exercise.student_visible_from) return 'hidden'
  if (exercise.student_visible) return 'visible'
  return 'scheduled'
}

// Derive exception visibility from raw API data:
// - outer null → no override (default)
// - { value: null } → hidden (null visible_from = hidden in DB)
// - { value: past date } → visible
// - { value: future date } → scheduled
function getExceptionVisibility(
  raw: { value: string | null } | null,
  parsed: Date | null,
): ExceptionVisibility {
  if (raw === null || raw === undefined) return 'default'
  if (raw.value === null) return 'hidden'
  if (parsed && parsed.getTime() <= Date.now()) return 'visible'
  return 'scheduled'
}

// Local state for an exception row
interface ExceptionRow {
  softDeadline: Date | null
  hardDeadline: Date | null
  visibleFrom: Date | null
  visibility: ExceptionVisibility
}

interface StudentExceptionRow extends ExceptionRow {
  studentId: string
  studentName: string
}

interface GroupExceptionRow extends ExceptionRow {
  groupId: number
  groupName: string
}

function exceptionFromApi(ex: ExceptionStudent, students: StudentParticipant[]): StudentExceptionRow {
  const student = students.find((s) => s.id === ex.student_id)
  const visibleFrom = parseIso(ex.student_visible_from?.value)
  return {
    studentId: ex.student_id,
    studentName: student ? `${student.given_name} ${student.family_name}` : ex.student_id,
    softDeadline: parseIso(ex.soft_deadline?.value),
    hardDeadline: parseIso(ex.hard_deadline?.value),
    visibleFrom,
    visibility: getExceptionVisibility(ex.student_visible_from, visibleFrom),
  }
}

function groupExceptionFromApi(ex: ExceptionGroup, groups: GroupResp[]): GroupExceptionRow {
  const group = groups.find((g) => Number(g.id) === ex.group_id)
  const visibleFrom = parseIso(ex.student_visible_from?.value)
  return {
    groupId: ex.group_id,
    groupName: group?.name ?? String(ex.group_id),
    softDeadline: parseIso(ex.soft_deadline?.value),
    hardDeadline: parseIso(ex.hard_deadline?.value),
    visibleFrom,
    visibility: getExceptionVisibility(ex.student_visible_from, visibleFrom),
  }
}

export default function ExerciseSettingsDialog({
  courseId,
  courseExerciseId,
  exercise,
  open,
  onClose,
}: {
  courseId: string
  courseExerciseId: string
  exercise: TeacherExerciseDetails
  open: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const updateExercise = useUpdateCourseExercise(courseId, courseExerciseId)
  const putExceptions = usePutExerciseExceptions(courseId, courseExerciseId)
  const deleteExceptions = useDeleteExerciseExceptions(courseId, courseExerciseId)
  const { data: participantsData } = useParticipants(courseId)
  const { data: groupsData } = useCourseGroups(courseId)

  const students = participantsData?.students ?? []
  const groups = groupsData ?? []

  const [titleAlias, setTitleAlias] = useState('')
  const [visibility, setVisibility] = useState<VisibilityMode>('visible')
  const [visibleFrom, setVisibleFrom] = useState<Date | null>(null)
  const [softDeadline, setSoftDeadline] = useState<Date | null>(null)
  const [hardDeadline, setHardDeadline] = useState<Date | null>(null)
  const [threshold, setThreshold] = useState('0')
  const [snackOpen, setSnackOpen] = useState(false)

  const [studentExceptions, setStudentExceptions] = useState<StudentExceptionRow[]>([])
  const [groupExceptions, setGroupExceptions] = useState<GroupExceptionRow[]>([])
  const [removedStudentIds, setRemovedStudentIds] = useState<string[]>([])
  const [removedGroupIds, setRemovedGroupIds] = useState<number[]>([])

  useEffect(() => {
    if (open) {
      setTitleAlias(exercise.title_alias ?? exercise.title)
      setVisibility(getVisibilityMode(exercise))
      setVisibleFrom(parseIso(exercise.student_visible_from))
      setSoftDeadline(parseIso(exercise.soft_deadline))
      setHardDeadline(parseIso(exercise.hard_deadline))
      setThreshold(String(exercise.threshold))
      setStudentExceptions(
        (exercise.exception_students ?? []).map((ex) => exceptionFromApi(ex, students)),
      )
      setGroupExceptions(
        (exercise.exception_groups ?? []).map((ex) => groupExceptionFromApi(ex, groups)),
      )
      setRemovedStudentIds([])
      setRemovedGroupIds([])
    }
  }, [exercise, open, students, groups])

  const thresholdNum = parseInt(threshold, 10)
  const thresholdValid = !isNaN(thresholdNum) && thresholdNum >= 0 && thresholdNum <= 100
  const visibleFromValid = visibility !== 'scheduled' || visibleFrom != null
  const canSave = thresholdValid && visibleFromValid
  const isSaving = updateExercise.isPending || putExceptions.isPending || deleteExceptions.isPending

  function handleSave() {
    const replace: Record<string, unknown> = {}
    const deleteFields: string[] = []

    // Title alias — only store if different from the exercise title
    if (titleAlias.trim() && titleAlias.trim() !== exercise.title) {
      replace.title_alias = titleAlias.trim()
    } else {
      deleteFields.push('TITLE_ALIAS')
    }

    // Threshold
    replace.threshold = thresholdNum

    // Visibility
    if (visibility === 'visible') {
      replace.student_visible = true
    } else if (visibility === 'hidden') {
      replace.student_visible = false
    } else if (visibleFrom) {
      replace.student_visible_from = visibleFrom.toISOString()
    }

    // Soft deadline
    if (softDeadline) {
      replace.soft_deadline = softDeadline.toISOString()
    } else {
      deleteFields.push('SOFT_DEADLINE')
    }

    // Hard deadline
    if (hardDeadline) {
      replace.hard_deadline = hardDeadline.toISOString()
    } else {
      deleteFields.push('HARD_DEADLINE')
    }

    // Save main settings
    updateExercise.mutate(
      {
        replace: Object.keys(replace).length > 0 ? replace : undefined,
        delete: deleteFields.length > 0 ? deleteFields : undefined,
      },
      {
        onSuccess: () => {
          // Save exceptions
          saveExceptions()
        },
      },
    )
  }

  function saveExceptions() {
    const hasValue = (ex: ExceptionRow) =>
      ex.softDeadline || ex.hardDeadline || ex.visibility !== 'default'

    // Exceptions with all-null fields should be treated as removals
    const emptyStudentIds = studentExceptions
      .filter((ex) => !hasValue(ex))
      .filter((ex) => exercise.exception_students?.some((orig) => orig.student_id === ex.studentId))
      .map((ex) => ex.studentId)
    const emptyGroupIds = groupExceptions
      .filter((ex) => !hasValue(ex))
      .filter((ex) => exercise.exception_groups?.some((orig) => orig.group_id === ex.groupId))
      .map((ex) => ex.groupId)

    const allRemovedStudentIds = [...removedStudentIds, ...emptyStudentIds]
    const allRemovedGroupIds = [...removedGroupIds, ...emptyGroupIds]

    const validStudentExceptions = studentExceptions.filter(hasValue)
    const validGroupExceptions = groupExceptions.filter(hasValue)

    const hasDeletes = allRemovedStudentIds.length > 0 || allRemovedGroupIds.length > 0
    const hasPuts = validStudentExceptions.length > 0 || validGroupExceptions.length > 0

    function visibleFromForApi(ex: ExceptionRow) {
      if (ex.visibility === 'default') return null
      if (ex.visibility === 'visible') return { value: '1979-10-12T00:00:00Z' }
      if (ex.visibility === 'hidden') return { value: null }
      if (ex.visibility === 'scheduled' && ex.visibleFrom) return { value: ex.visibleFrom.toISOString() }
      return null
    }

    const onDone = () => {
      if (hasPuts) {
        putExceptions.mutate(
          {
            exception_students: validStudentExceptions.map((ex) => ({
              student_id: ex.studentId,
              soft_deadline: ex.softDeadline ? { value: ex.softDeadline.toISOString() } : null,
              hard_deadline: ex.hardDeadline ? { value: ex.hardDeadline.toISOString() } : null,
              student_visible_from: visibleFromForApi(ex),
            })),
            exception_groups: validGroupExceptions.map((ex) => ({
              group_id: ex.groupId,
              soft_deadline: ex.softDeadline ? { value: ex.softDeadline.toISOString() } : null,
              hard_deadline: ex.hardDeadline ? { value: ex.hardDeadline.toISOString() } : null,
              student_visible_from: visibleFromForApi(ex),
            })),
          },
          {
            onSuccess: () => {
              onClose()
              setSnackOpen(true)
            },
          },
        )
      } else {
        onClose()
        setSnackOpen(true)
      }
    }

    if (hasDeletes) {
      deleteExceptions.mutate(
        {
          exception_students: allRemovedStudentIds.length > 0 ? allRemovedStudentIds : undefined,
          exception_groups: allRemovedGroupIds.length > 0 ? allRemovedGroupIds : undefined,
        },
        { onSuccess: onDone },
      )
    } else {
      onDone()
    }
  }

  // Students available for adding (not already in exceptions)
  const availableStudents = students.filter(
    (s) => !studentExceptions.some((ex) => ex.studentId === s.id),
  )

  // Groups available for adding
  const availableGroups = groups.filter(
    (g) => !groupExceptions.some((ex) => ex.groupId === Number(g.id)),
  )

  function addStudentException(student: StudentParticipant) {
    setStudentExceptions((prev) => [
      ...prev,
      {
        studentId: student.id,
        studentName: `${student.given_name} ${student.family_name}`,
        softDeadline: null,
        hardDeadline: null,
        visibleFrom: null,
        visibility: 'default',
      },
    ])
    // If was previously removed, un-remove it
    setRemovedStudentIds((prev) => prev.filter((id) => id !== student.id))
  }

  function removeStudentException(studentId: string) {
    setStudentExceptions((prev) => prev.filter((ex) => ex.studentId !== studentId))
    // Track removal only if it existed in the original data
    if (exercise.exception_students?.some((ex) => ex.student_id === studentId)) {
      setRemovedStudentIds((prev) => [...prev, studentId])
    }
  }

  function updateStudentException(studentId: string, update: Partial<ExceptionRow>) {
    setStudentExceptions((prev) =>
      prev.map((ex) => (ex.studentId === studentId ? { ...ex, ...update } : ex)),
    )
  }

  function addGroupException(group: GroupResp) {
    setGroupExceptions((prev) => [
      ...prev,
      {
        groupId: Number(group.id),
        groupName: group.name,
        softDeadline: null,
        hardDeadline: null,
        visibleFrom: null,
        visibility: 'default',
      },
    ])
    setRemovedGroupIds((prev) => prev.filter((id) => id !== Number(group.id)))
  }

  function removeGroupException(groupId: number) {
    setGroupExceptions((prev) => prev.filter((ex) => ex.groupId !== groupId))
    if (exercise.exception_groups?.some((ex) => ex.group_id === groupId)) {
      setRemovedGroupIds((prev) => [...prev, groupId])
    }
  }

  function updateGroupException(groupId: number, update: Partial<ExceptionRow>) {
    setGroupExceptions((prev) =>
      prev.map((ex) => (ex.groupId === groupId ? { ...ex, ...update } : ex)),
    )
  }

  const hasExceptions = studentExceptions.length > 0 || groupExceptions.length > 0
  const hasOriginalExceptions =
    (exercise.exception_students?.length ?? 0) > 0 || (exercise.exception_groups?.length ?? 0) > 0

  return (
    <>
      <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
        <DialogTitle>{exercise.title}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, pt: '8px !important' }}>
          <TextField
            label={t('exercises.titleAlias')}
            value={titleAlias}
            onChange={(e) => setTitleAlias(e.target.value)}
            inputProps={{ maxLength: 100 }}
            size="small"
          />

          <FormControl size="small">
            <InputLabel>{t('exercises.visibility')}</InputLabel>
            <Select
              value={visibility}
              label={t('exercises.visibility')}
              onChange={(e) => setVisibility(e.target.value as VisibilityMode)}
            >
              <MenuItem value="visible">{t('exercises.visible')}</MenuItem>
              <MenuItem value="hidden">{t('exercises.hidden')}</MenuItem>
              <MenuItem value="scheduled">{t('exercises.visibleFrom')}</MenuItem>
            </Select>
          </FormControl>

          {visibility === 'scheduled' && (
            <DateTimePicker
              label={t('exercises.visibleFrom')}
              value={visibleFrom}
              onChange={setVisibleFrom}
              slotProps={{
                textField: { size: 'small', error: !visibleFromValid },
              }}
            />
          )}

          <Divider />

          <DateTimePicker
            label={t('exercises.deadline')}
            value={softDeadline}
            onChange={setSoftDeadline}
            slotProps={{
              textField: { size: 'small' },
              field: { clearable: true },
            }}
          />

          <DateTimePicker
            label={t('exercises.closingTime')}
            value={hardDeadline}
            onChange={setHardDeadline}
            slotProps={{
              textField: { size: 'small' },
              field: { clearable: true },
            }}
          />

          <TextField
            label={t('exercises.threshold')}
            type="number"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            inputProps={{ min: 0, max: 100 }}
            error={!thresholdValid}
            size="small"
          />

          <Divider />

          {/* Exceptions */}
          <Accordion
            disableGutters
            elevation={0}
            defaultExpanded={hasOriginalExceptions}
            sx={{
              '&:before': { display: 'none' },
              border: 1,
              borderColor: 'divider',
              borderRadius: '8px !important',
            }}
          >
            <AccordionSummary expandIcon={<ExpandMoreOutlined />}>
              <Typography variant="subtitle2">{t('exercises.exceptions')}</Typography>
            </AccordionSummary>
            <AccordionDetails sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 0 }}>
              {/* Student exceptions */}
              {studentExceptions.length > 0 && (
                <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                  {t('exercises.studentExceptions')}
                </Typography>
              )}
              {studentExceptions.map((ex) => (
                <ExceptionRowUI
                  key={ex.studentId}
                  label={ex.studentName}
                  exception={ex}
                  onUpdate={(update) => updateStudentException(ex.studentId, update)}
                  onRemove={() => removeStudentException(ex.studentId)}
                  t={t}
                />
              ))}
              {availableStudents.length > 0 && (
                <Autocomplete
                  options={availableStudents}
                  getOptionLabel={(s) => `${s.given_name} ${s.family_name}`}
                  noOptionsText={t('exercises.noOptions')}
                  autoHighlight
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      size="small"
                      placeholder={t('exercises.addStudentException')}
                    />
                  )}
                  onChange={(_, value) => {
                    if (value) addStudentException(value)
                  }}
                  value={null}
                  blurOnSelect
                  size="small"
                />
              )}

              {/* Group exceptions */}
              {(groupExceptions.length > 0 || studentExceptions.length > 0) && groups.length > 0 && (
                <Divider sx={{ my: 0.5 }} />
              )}
              {groupExceptions.length > 0 && (
                <Typography variant="caption" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                  {t('exercises.groupExceptions')}
                </Typography>
              )}
              {groupExceptions.map((ex) => (
                <ExceptionRowUI
                  key={ex.groupId}
                  label={ex.groupName}
                  exception={ex}
                  onUpdate={(update) => updateGroupException(ex.groupId, update)}
                  onRemove={() => removeGroupException(ex.groupId)}
                  t={t}
                />
              ))}
              {availableGroups.length > 0 && (
                <Autocomplete
                  options={availableGroups}
                  getOptionLabel={(g) => g.name}
                  noOptionsText={t('exercises.noOptions')}
                  autoHighlight
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      size="small"
                      placeholder={t('exercises.addGroupException')}
                    />
                  )}
                  onChange={(_, value) => {
                    if (value) addGroupException(value)
                  }}
                  value={null}
                  blurOnSelect
                  size="small"
                />
              )}

              {!hasExceptions && availableStudents.length === 0 && availableGroups.length === 0 && (
                <Typography variant="body2" color="text.secondary">
                  {t('exercises.noExceptions')}
                </Typography>
              )}
            </AccordionDetails>
          </Accordion>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>{t('general.cancel')}</Button>
          <Button
            onClick={handleSave}
            variant="contained"
            disabled={!canSave || isSaving}
          >
            {isSaving ? t('general.saving') : t('general.save')}
          </Button>
        </DialogActions>
      </Dialog>
      <Snackbar
        open={snackOpen}
        autoHideDuration={3000}
        onClose={() => setSnackOpen(false)}
        message={t('library.exerciseSaved')}
      />
    </>
  )
}

function ExceptionRowUI({
  label,
  exception,
  onUpdate,
  onRemove,
  t,
}: {
  label: string
  exception: ExceptionRow
  onUpdate: (update: Partial<ExceptionRow>) => void
  onRemove: () => void
  t: (key: string) => string
}) {
  function handleVisibilityChange(mode: ExceptionVisibility) {
    if (mode === 'default' || mode === 'visible' || mode === 'hidden') {
      onUpdate({ visibility: mode, visibleFrom: null })
    } else {
      onUpdate({ visibility: 'scheduled', visibleFrom: null })
    }
  }

  return (
    <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography variant="subtitle2">{label}</Typography>
        <IconButton size="small" onClick={onRemove}>
          <DeleteOutlined fontSize="small" />
        </IconButton>
      </Box>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
        <FormControl size="small" fullWidth>
          <InputLabel>{t('exercises.visibility')}</InputLabel>
          <Select
            value={exception.visibility}
            label={t('exercises.visibility')}
            onChange={(e) => handleVisibilityChange(e.target.value as ExceptionVisibility)}
          >
            <MenuItem value="default">{t('exercises.noOverride')}</MenuItem>
            <MenuItem value="visible">{t('exercises.visible')}</MenuItem>
            <MenuItem value="hidden">{t('exercises.hidden')}</MenuItem>
            <MenuItem value="scheduled">{t('exercises.visibleFrom')}</MenuItem>
          </Select>
        </FormControl>
        {exception.visibility === 'scheduled' && (
          <DateTimePicker
            label={t('exercises.visibleFrom')}
            value={exception.visibleFrom}
            onChange={(v) => onUpdate({ visibleFrom: v })}
            slotProps={{
              textField: { size: 'small', fullWidth: true },
            }}
          />
        )}
        <DateTimePicker
          label={t('exercises.deadline')}
          value={exception.softDeadline}
          onChange={(v) => onUpdate({ softDeadline: v })}
          slotProps={{
            textField: { size: 'small', fullWidth: true },
            field: { clearable: true },
          }}
        />
        <DateTimePicker
          label={t('exercises.closingTime')}
          value={exception.hardDeadline}
          onChange={(v) => onUpdate({ hardDeadline: v })}
          slotProps={{
            textField: { size: 'small', fullWidth: true },
            field: { clearable: true },
          }}
        />
      </Box>
    </Box>
  )
}
