import { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  Box,
  Button,
  TextField,
  Typography,
  List,
  ListItem,
  ListItemAvatar,
  ListItemText,
  Avatar,
  Select,
  MenuItem,
  Divider,
  CircularProgress,
  IconButton,
  type SelectChangeEvent,
} from '@mui/material'
import {
  PersonOutlined,
  PublicOutlined,
  GroupOutlined,
  ArrowBackOutlined,
} from '@mui/icons-material'
import { useDirAccesses, usePutDirAccess } from '../../api/library.ts'
import { ApiResponseError } from '../../api/client.ts'
import { useAuth } from '../../auth/AuthContext.tsx'
import type {
  DirAccessLevel,
} from '../../api/types.ts'

const ACCESS_LEVELS: DirAccessLevel[] = ['PR', 'PRA', 'PRAW', 'PRAWM']
const ACCESS_ORDER: DirAccessLevel[] = ['P', 'PR', 'PRA', 'PRAW', 'PRAWM']

function accessRank(level: DirAccessLevel): number {
  return ACCESS_ORDER.indexOf(level)
}

function accessLabel(level: DirAccessLevel, isDir: boolean, t: (k: string) => string): string {
  switch (level) {
    case 'PR': return t('library.permissionPR')
    case 'PRA': return isDir ? t('library.permissionPRA') : t('library.permissionPR')
    case 'PRAW': return t('library.permissionPRAW')
    case 'PRAWM': return t('library.permissionPRAWM')
    default: return level
  }
}

interface DirView {
  dirId: string
  name: string
  isDir: boolean
  parentDirId?: string
}

interface ShareDialogProps {
  dirId: string | undefined
  parentDirId?: string
  itemName: string
  isDir: boolean
  open: boolean
  onClose: () => void
}

export default function ShareDialog({ dirId, parentDirId, itemName, isDir, open, onClose }: ShareDialogProps) {
  const { t } = useTranslation()
  const { username, activeRole } = useAuth()
  const isAdmin = activeRole === 'admin'

  // Navigation stack: the initial view is derived from props, clicking inherited dirs pushes new views
  const [navStack, setNavStack] = useState<DirView[]>([])

  // Reset stack when dialog opens/closes or props change
  useEffect(() => {
    if (open && dirId) {
      setNavStack([{ dirId, name: itemName, isDir, parentDirId }])
    }
  }, [open, dirId, itemName, isDir, parentDirId])

  const current = navStack[navStack.length - 1]
  const canGoBack = navStack.length > 1

  function navigateToDir(targetDirId: string, targetName: string) {
    setNavStack((prev) => [...prev, { dirId: targetDirId, name: targetName, isDir: true }])
    setEmail('')
    setEmailError('')
  }

  function goBack() {
    setNavStack((prev) => prev.slice(0, -1))
    setEmail('')
    setEmailError('')
  }

  const activeDirId = current?.dirId
  const activeIsDir = current?.isDir ?? true

  const { data, isLoading } = useDirAccesses(open ? activeDirId : undefined)
  const putAccess = usePutDirAccess(activeDirId)

  const [email, setEmail] = useState('')
  const [emailError, setEmailError] = useState('')
  const [adding, setAdding] = useState(false)

  function handleClose() {
    setEmail('')
    setEmailError('')
    onClose()
  }

  async function handleAddByEmail() {
    const value = email.trim()
    if (!value || adding) return

    setEmailError('')
    setAdding(true)

    try {
      if (value.toLowerCase() === 'all' && isAdmin) {
        await putAccess.mutateAsync({ any_access: true, access_level: 'PR' })
      } else {
        await putAccess.mutateAsync({ email: value, access_level: 'PR' })
      }
      setEmail('')
    } catch (err) {
      if (err instanceof ApiResponseError && err.errorBody?.code === 'ACCOUNT_NOT_FOUND') {
        setEmailError(t('library.emailNotFound'))
      } else {
        setEmailError(err instanceof Error ? err.message : String(err))
      }
    } finally {
      setAdding(false)
    }
  }

  function handleAccessChange(e: SelectChangeEvent, groupId: string, isAnyAccess: boolean) {
    const val = e.target.value
    if (val === 'remove') {
      if (isAnyAccess) {
        putAccess.mutate({ any_access: true, access_level: null })
      } else {
        putAccess.mutate({ group_id: groupId, access_level: null })
      }
    } else {
      const level = val as DirAccessLevel
      if (isAnyAccess) {
        putAccess.mutate({ any_access: true, access_level: level })
      } else {
        putAccess.mutate({ group_id: groupId, access_level: level })
      }
    }
  }

  // Build sorted list of direct permissions
  const directRows: Array<{
    key: string
    label: string
    secondary: string
    icon: 'person' | 'public' | 'group'
    groupId: string
    access: DirAccessLevel
    isCurrentUser: boolean
    isAnyAccess: boolean
  }> = []

  if (data) {
    if (data.direct_any) {
      directRows.push({
        key: 'any',
        label: t('library.allUsers'),
        secondary: '',
        icon: 'public',
        groupId: '',
        access: data.direct_any.access,
        isCurrentUser: false,
        isAnyAccess: true,
      })
    }

    for (const acc of data.direct_accounts) {
      const isSelf = acc.username === username
      directRows.push({
        key: `acc-${acc.username}`,
        label: `${acc.given_name} ${acc.family_name}${isSelf ? ` (${t('library.you')})` : ''}`,
        secondary: acc.email ?? acc.username,
        icon: 'person',
        groupId: acc.group_id,
        access: acc.access,
        isCurrentUser: isSelf,
        isAnyAccess: false,
      })
    }

    for (const grp of data.direct_groups) {
      directRows.push({
        key: `grp-${grp.id}`,
        label: grp.name,
        secondary: '',
        icon: 'group',
        groupId: grp.id,
        access: grp.access,
        isCurrentUser: false,
        isAnyAccess: false,
      })
    }
  }

  // Sort: current user first, then by access level desc, then by name, "all users" last
  directRows.sort((a, b) => {
    if (a.isCurrentUser !== b.isCurrentUser) return a.isCurrentUser ? -1 : 1
    if (a.isAnyAccess !== b.isAnyAccess) return a.isAnyAccess ? 1 : -1
    const rankDiff = accessRank(b.access) - accessRank(a.access)
    if (rankDiff !== 0) return rankDiff
    return a.label.localeCompare(b.label)
  })

  // Build inherited permissions grouped by parent dir
  type InheritedGroup = {
    dirId: string
    dirName: string
    isExerciseParent: boolean
    rows: Array<{
      key: string
      label: string
      secondary: string
      icon: 'person' | 'public' | 'group'
      access: DirAccessLevel
    }>
  }

  const inheritedGroups: InheritedGroup[] = []

  if (data) {
    const dirMap = new Map<string, InheritedGroup>()

    function getOrCreateGroup(fromId: string, fromName: string): InheritedGroup {
      let g = dirMap.get(fromId)
      if (!g) {
        g = {
          dirId: fromId,
          dirName: fromName,
          isExerciseParent: !activeIsDir && fromId === current?.parentDirId,
          rows: [],
        }
        dirMap.set(fromId, g)
        inheritedGroups.push(g)
      }
      return g
    }

    if (data.inherited_any?.inherited_from) {
      const f = data.inherited_any.inherited_from
      getOrCreateGroup(f.id, f.name).rows.push({
        key: 'inh-any',
        label: t('library.allUsers'),
        secondary: '',
        icon: 'public',
        access: data.inherited_any.access,
      })
    }

    for (const acc of data.inherited_accounts) {
      if (!acc.inherited_from) continue
      const f = acc.inherited_from
      getOrCreateGroup(f.id, f.name).rows.push({
        key: `inh-acc-${acc.username}`,
        label: `${acc.given_name} ${acc.family_name}${acc.username === username ? ` (${t('library.you')})` : ''}`,
        secondary: acc.email ?? acc.username,
        icon: 'person',
        access: acc.access,
      })
    }

    for (const grp of data.inherited_groups) {
      if (!grp.inherited_from) continue
      const f = grp.inherited_from
      getOrCreateGroup(f.id, f.name).rows.push({
        key: `inh-grp-${grp.id}`,
        label: grp.name,
        secondary: '',
        icon: 'group',
        access: grp.access,
      })
    }

    // Sort rows within each inherited group: "all users" last
    for (const g of inheritedGroups) {
      g.rows.sort((a, b) => {
        if (a.icon === 'public' && b.icon !== 'public') return 1
        if (a.icon !== 'public' && b.icon === 'public') return -1
        return 0
      })
    }

    // Sort inherited groups by dir ID desc (closest parent first)
    inheritedGroups.sort((a, b) => b.dirId.localeCompare(a.dirId))
  }

  const availableLevels = activeIsDir ? ACCESS_LEVELS : ACCESS_LEVELS.filter((l) => l !== 'PRA')

  function renderIcon(icon: 'person' | 'public' | 'group') {
    switch (icon) {
      case 'public': return <PublicOutlined />
      case 'group': return <GroupOutlined />
      default: return <PersonOutlined />
    }
  }

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="sm"
      fullWidth
      TransitionProps={{ onEntered: (node) => { (node as HTMLElement).querySelector('input')?.focus() } }}
    >
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        {canGoBack && (
          <IconButton size="small" onClick={goBack} sx={{ ml: -1 }}>
            <ArrowBackOutlined fontSize="small" />
          </IconButton>
        )}
        {t('library.share')}: {current?.name ?? itemName}
      </DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        {/* Email input */}
        <Box sx={{ display: 'flex', gap: 1, mb: 2 }}>
          <TextField
            fullWidth
            size="small"
            placeholder={t('library.shareEmailHelp')}
            value={email}
            onChange={(e) => { setEmail(e.target.value); setEmailError('') }}
            autoFocus
            error={!!emailError}
            helperText={emailError}
            disabled={adding}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && email.trim() && !adding) handleAddByEmail()
            }}
            slotProps={{
              input: {
                endAdornment: adding ? <CircularProgress size={20} /> : undefined,
              },
            }}
          />
          <Button
            variant="contained"
            size="small"
            onClick={handleAddByEmail}
            disabled={!email.trim() || adding}
            sx={{ alignSelf: 'flex-start', height: 40 }}
          >
            {t('library.share')}
          </Button>
        </Box>

        {isLoading ? (
          <Box display="flex" justifyContent="center" py={4}>
            <CircularProgress />
          </Box>
        ) : (
          <>
            {/* Direct permissions */}
            {directRows.length > 0 && (
              <List dense disablePadding>
                {directRows.map((row) => (
                  <ListItem
                    key={row.key}
                    secondaryAction={
                      <Select
                        size="small"
                        value={row.access}
                        onChange={(e) => handleAccessChange(e, row.groupId, row.isAnyAccess)}
                        disabled={row.isCurrentUser || putAccess.isPending}
                        variant="outlined"
                        sx={{
                          fontSize: '0.875rem',
                          borderRadius: 1,
                          '.MuiOutlinedInput-notchedOutline': { border: 'none' },
                          '&:hover:not(.Mui-disabled)': { bgcolor: 'action.hover' },
                        }}
                      >
                        {availableLevels.map((level) => (
                          <MenuItem key={level} value={level}>
                            {accessLabel(level, activeIsDir, t)}
                          </MenuItem>
                        ))}
                        <Divider />
                        <MenuItem value="remove">
                          <Typography color="error" variant="body2">{t('library.removeAccess')}</Typography>
                        </MenuItem>
                      </Select>
                    }
                    sx={{ pr: 18 }}
                  >
                    <ListItemAvatar sx={{ minWidth: 40 }}>
                      <Avatar sx={{ width: 32, height: 32, bgcolor: 'action.selected' }}>
                        {renderIcon(row.icon)}
                      </Avatar>
                    </ListItemAvatar>
                    <ListItemText
                      primary={row.label}
                      secondary={row.secondary || undefined}
                      slotProps={{
                        primary: { variant: 'body2' },
                        secondary: { variant: 'caption' },
                      }}
                    />
                  </ListItem>
                ))}
              </List>
            )}

            {/* Inherited permissions */}
            {inheritedGroups.map((group) => (
              <Box key={group.dirId} sx={{ mt: 2 }}>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
                  {t('library.inheritedFrom')}{' '}
                  <Typography
                    component="a"
                    variant="caption"
                    href="#"
                    onClick={(e) => {
                      e.preventDefault()
                      navigateToDir(group.dirId, group.dirName)
                    }}
                    sx={{ color: 'primary.main', textDecoration: 'none', cursor: 'pointer', '&:hover': { textDecoration: 'underline' } }}
                  >
                    {group.dirName}
                  </Typography>
                  {group.isExerciseParent && ` ${t('library.thisDirectory')}`}
                </Typography>
                <List dense disablePadding>
                  {group.rows.map((row) => (
                    <ListItem key={row.key} sx={{ pr: 18 }}>
                      <ListItemAvatar sx={{ minWidth: 40 }}>
                        <Avatar sx={{ width: 32, height: 32, bgcolor: 'action.selected' }}>
                          {renderIcon(row.icon)}
                        </Avatar>
                      </ListItemAvatar>
                      <ListItemText
                        primary={row.label}
                        secondary={row.secondary || undefined}
                        slotProps={{
                          primary: { variant: 'body2' },
                          secondary: { variant: 'caption' },
                        }}
                      />
                      <Typography variant="body2" color="text.secondary" sx={{ position: 'absolute', right: 16 }}>
                        {accessLabel(row.access, activeIsDir, t)}
                      </Typography>
                    </ListItem>
                  ))}
                </List>
              </Box>
            ))}
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
