import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import {
  AddOutlined,
  DeleteOutlined,
  EditOutlined,
  ScheduleOutlined,
} from '@mui/icons-material'
import {
  useAdminSystemMessages,
  useCreateSystemMessage,
  useDeleteSystemMessage,
  useUpdateSystemMessage,
  type AdminSystemMessage,
  type MessageSeverity,
} from '../../api/messages.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'

/**
 * `<input type="datetime-local">` speaks "YYYY-MM-DDTHH:mm" in *local* time and knows nothing about
 * zones; core speaks ISO-8601 with an offset. These two convert between them, and doing it wrong is
 * the classic way to schedule maintenance for the wrong hour — so the conversion goes through Date
 * rather than string surgery, which is what makes the offset apply.
 */
function isoToLocalInput(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function localInputToIso(local: string): string | null {
  if (!local) return null
  const d = new Date(local)
  return Number.isNaN(d.getTime()) ? null : d.toISOString()
}

const EMPTY: AdminSystemMessage = {
  id: '',
  message: '',
  severity: 'INFO',
  link_url: undefined,
  link_label: undefined,
  visible_from: null,
  visible_until: null,
  for_students: true,
  for_teachers: true,
  for_admins: true,
}

/** Where a message is in its life, from the schedule alone. */
function scheduleState(m: AdminSystemMessage, now = Date.now()) {
  const from = m.visible_from ? new Date(m.visible_from).getTime() : null
  const until = m.visible_until ? new Date(m.visible_until).getTime() : null
  if (from !== null && from > now) return 'scheduled' as const
  if (until !== null && until <= now) return 'expired' as const
  return 'live' as const
}

function MessageDialog({
  open,
  initial,
  onClose,
  onSave,
  saving,
}: {
  open: boolean
  initial: AdminSystemMessage
  onClose: () => void
  onSave: (m: AdminSystemMessage) => void
  saving: boolean
}) {
  const { t } = useTranslation()
  const [draft, setDraft] = useState(initial)
  const set = <K extends keyof AdminSystemMessage>(k: K, v: AdminSystemMessage[K]) =>
    setDraft((d) => ({ ...d, [k]: v }))

  // The same rules core enforces, checked here so the answer arrives while typing rather than as a
  // 400 after pressing save. Core still enforces them — this is a convenience, not the guard.
  const linkHalfGiven = Boolean(draft.link_url) !== Boolean(draft.link_label)
  const badWindow =
    Boolean(draft.visible_from) &&
    Boolean(draft.visible_until) &&
    new Date(draft.visible_until!).getTime() <= new Date(draft.visible_from!).getTime()
  const noAudience = !draft.for_students && !draft.for_teachers && !draft.for_admins
  const invalid = !draft.message.trim() || linkHalfGiven || badWindow || noAudience

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{initial.id ? t('admin.messages.edit') : t('admin.messages.create')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5} sx={{ mt: 1 }}>
          <TextField
            label={t('admin.messages.text')}
            value={draft.message}
            onChange={(e) => set('message', e.target.value)}
            multiline
            minRows={2}
            autoFocus
            fullWidth
            required
          />

          <TextField
            select
            label={t('admin.messages.severity')}
            value={draft.severity}
            onChange={(e) => set('severity', e.target.value as MessageSeverity)}
            helperText={
              draft.severity === 'URGENT'
                ? t('admin.messages.urgentHint')
                : t('admin.messages.infoHint')
            }
          >
            <MenuItem value="URGENT">{t('admin.messages.urgent')}</MenuItem>
            <MenuItem value="INFO">{t('admin.messages.info')}</MenuItem>
          </TextField>

          <Stack direction="row" spacing={2}>
            <TextField
              label={t('admin.messages.linkUrl')}
              value={draft.link_url ?? ''}
              onChange={(e) => set('link_url', e.target.value || undefined)}
              fullWidth
              error={linkHalfGiven}
            />
            <TextField
              label={t('admin.messages.linkLabel')}
              value={draft.link_label ?? ''}
              onChange={(e) => set('link_label', e.target.value || undefined)}
              fullWidth
              error={linkHalfGiven}
              helperText={linkHalfGiven ? t('admin.messages.linkBothRequired') : ' '}
            />
          </Stack>

          <Stack direction="row" spacing={2}>
            <TextField
              label={t('admin.messages.visibleFrom')}
              type="datetime-local"
              value={isoToLocalInput(draft.visible_from)}
              onChange={(e) => set('visible_from', localInputToIso(e.target.value))}
              slotProps={{ inputLabel: { shrink: true } }}
              fullWidth
              helperText={t('admin.messages.emptyMeansNow')}
            />
            <TextField
              label={t('admin.messages.visibleUntil')}
              type="datetime-local"
              value={isoToLocalInput(draft.visible_until)}
              onChange={(e) => set('visible_until', localInputToIso(e.target.value))}
              slotProps={{ inputLabel: { shrink: true } }}
              fullWidth
              error={badWindow}
              helperText={badWindow ? t('admin.messages.badWindow') : t('admin.messages.emptyMeansForever')}
            />
          </Stack>

          <Box>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>
              {t('admin.messages.audience')}
            </Typography>
            <Stack direction="row" spacing={1}>
              {(
                [
                  ['for_students', t('nav.roleStudent')],
                  ['for_teachers', t('nav.roleTeacher')],
                  ['for_admins', t('nav.roleAdmin')],
                ] as const
              ).map(([key, label]) => (
                <FormControlLabel
                  key={key}
                  control={
                    <Checkbox checked={draft[key]} onChange={(e) => set(key, e.target.checked)} />
                  }
                  label={label}
                />
              ))}
            </Stack>
            {noAudience && <Alert severity="warning">{t('admin.messages.noAudience')}</Alert>}
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('general.cancel')}</Button>
        <Button
          variant="contained"
          disabled={invalid || saving}
          onClick={() => onSave(draft)}
        >
          {t('general.save')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

export default function SystemMessagesPage() {
  const { t } = useTranslation()
  usePageTitle(t('admin.messages.title'))

  const { data: messages, isLoading } = useAdminSystemMessages()
  const create = useCreateSystemMessage()
  const update = useUpdateSystemMessage()
  const remove = useDeleteSystemMessage()

  const [editing, setEditing] = useState<AdminSystemMessage | null>(null)

  const save = (m: AdminSystemMessage) => {
    const done = () => setEditing(null)
    if (m.id) update.mutate(m, { onSuccess: done })
    else {
      // A new message has no id yet — core assigns it — so the field is dropped rather than sent
      // empty, which the create endpoint has no meaning for.
      const draft = { ...m }
      delete (draft as Partial<AdminSystemMessage>).id
      create.mutate(draft, { onSuccess: done })
    }
  }

  return (
    <Box>
      <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
        <Typography variant="h5">{t('admin.messages.title')}</Typography>
        <Button
          variant="contained"
          startIcon={<AddOutlined />}
          onClick={() => setEditing({ ...EMPTY })}
        >
          {t('admin.messages.create')}
        </Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {t('admin.messages.intro')}
      </Typography>

      {isLoading && <CircularProgress />}

      {!isLoading && (messages ?? []).length === 0 && (
        <Typography color="text.secondary">{t('admin.messages.none')}</Typography>
      )}

      <Stack spacing={1.5}>
        {(messages ?? []).map((m) => {
          const state = scheduleState(m)
          return (
            <Paper key={m.id} variant="outlined" sx={{ p: 2 }}>
              <Stack direction="row" alignItems="flex-start" spacing={2}>
                <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                  <Stack direction="row" spacing={1} sx={{ mb: 0.75 }} flexWrap="wrap" useFlexGap>
                    <Chip
                      size="small"
                      label={m.severity === 'URGENT' ? t('admin.messages.urgent') : t('admin.messages.info')}
                      color={m.severity === 'URGENT' ? 'warning' : 'default'}
                    />
                    {/* Whether it is on screen right now, which the dates alone make you compute in
                        your head — and the reason someone would open this page at all. */}
                    <Chip
                      size="small"
                      variant={state === 'live' ? 'filled' : 'outlined'}
                      color={state === 'live' ? 'success' : 'default'}
                      icon={state === 'scheduled' ? <ScheduleOutlined /> : undefined}
                      label={t(`admin.messages.state.${state}`)}
                    />
                    {!(m.for_students && m.for_teachers && m.for_admins) && (
                      <Chip
                        size="small"
                        variant="outlined"
                        label={[
                          m.for_students ? t('nav.roleStudent') : null,
                          m.for_teachers ? t('nav.roleTeacher') : null,
                          m.for_admins ? t('nav.roleAdmin') : null,
                        ]
                          .filter(Boolean)
                          .join(', ')}
                      />
                    )}
                  </Stack>
                  <Typography sx={{ wordBreak: 'break-word' }}>{m.message}</Typography>
                  {m.link_url && (
                    <Typography variant="caption" color="text.secondary">
                      {m.link_label} → {m.link_url}
                    </Typography>
                  )}
                  {(m.visible_from || m.visible_until) && (
                    <Typography variant="caption" color="text.secondary" display="block">
                      {isoToLocalInput(m.visible_from).replace('T', ' ') || '…'}
                      {' — '}
                      {isoToLocalInput(m.visible_until).replace('T', ' ') || '…'}
                    </Typography>
                  )}
                </Box>
                <Stack direction="row">
                  <Tooltip title={t('general.edit')}>
                    <IconButton onClick={() => setEditing(m)}>
                      <EditOutlined />
                    </IconButton>
                  </Tooltip>
                  <Tooltip title={t('general.delete')}>
                    <IconButton
                      onClick={() => {
                        if (window.confirm(t('admin.messages.confirmDelete'))) remove.mutate(m.id)
                      }}
                    >
                      <DeleteOutlined />
                    </IconButton>
                  </Tooltip>
                </Stack>
              </Stack>
            </Paper>
          )
        })}
      </Stack>

      {editing && (
        <MessageDialog
          // Keyed by id so opening a different message remounts the dialog and its draft state,
          // rather than showing the previous one's values under a new title.
          key={editing.id || 'new'}
          open
          initial={editing}
          onClose={() => setEditing(null)}
          onSave={save}
          saving={create.isPending || update.isPending}
        />
      )}
    </Box>
  )
}
