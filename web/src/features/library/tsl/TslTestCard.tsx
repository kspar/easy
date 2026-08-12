import { useId, useState } from 'react'
import {
  Box,
  Chip,
  Collapse,
  FormControl,
  FormControlLabel,
  IconButton,
  InputLabel,
  ListItemIcon,
  ListItemText,
  ListSubheader,
  Menu,
  MenuItem,
  Paper,
  Select,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import {
  ArrowDownwardOutlined,
  ArrowUpwardOutlined,
  ContentCopyOutlined,
  DeleteOutlineOutlined,
  DriveFileRenameOutlineOutlined,
  ExpandMoreOutlined,
  MoreVertOutlined,
  VisibilityOffOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import TslTestBody from './TslTestBody.tsx'
import {
  createTest,
  defaultTestName,
  isEditableType,
  pointsWeightField,
  setOrDefault,
  TEST_TYPE_GROUPS,
  testDefaultName,
  visibleToUserField,
  type TslTest,
} from './tslModel.ts'

export interface TestCardActions {
  onChange: (next: TslTest) => void
  onDuplicate: () => void
  onDelete: () => void
  onMove: (delta: number) => void
}

export default function TslTestCard({
  test,
  index,
  count,
  editing,
  expanded,
  onToggle,
  actions,
}: {
  test: TslTest
  index: number
  count: number
  editing: boolean
  expanded: boolean
  onToggle: (open: boolean) => void
  actions: TestCardActions
}) {
  const { t } = useTranslation()
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)
  const [renaming, setRenaming] = useState(false)
  // MUI only wires InputLabel to a Select when both carry ids; without them the control has no
  // accessible name at all.
  const typeLabelId = useId()

  // Instance-aware, not type-aware: a collapsed test's name depends on its scope and target, so
  // every `contains_test` would otherwise read as the same generic label in the list.
  const title = test.name?.trim() ? test.name : testDefaultName(test, t)

  /**
   * Switching type replaces the body wholesale — the fields of one TSL test mean nothing to
   * another. The id and the name are what carry over.
   */
  function changeType(type: string) {
    if (!isEditableType(type)) return
    const fresh = createTest(type, test.id, t)
    const keptName = test.name?.trim() && test.name !== testDefaultName(test, t) ? test.name : null
    actions.onChange({ ...fresh, name: keptName })
  }

  return (
    // Deliberately not MUI's Accordion: its summary renders a <button>, and this header carries
    // buttons of its own, which is invalid HTML (and React says so at runtime).
    <Paper variant="outlined" sx={{ mb: 1 }}>
      <Box display="flex" alignItems="center" gap={1} px={1} py={0.5}>
        <IconButton
          size="small"
          onClick={() => onToggle(!expanded)}
          aria-label={expanded ? t('tsl.collapseTest') : t('tsl.expandTest')}
          aria-expanded={expanded}
        >
          <ExpandMoreOutlined
            fontSize="small"
            sx={{ transform: expanded ? 'rotate(180deg)' : 'none', transition: '.15s' }}
          />
        </IconButton>
        <Box display="flex" alignItems="center" gap={1} flex={1} minWidth={0}>
          {renaming ? (
            <TextField
              value={test.name ?? ''}
              onChange={(e) => actions.onChange({ ...test, name: e.target.value })}
              onClick={(e) => e.stopPropagation()}
              onKeyDown={(e) => {
                e.stopPropagation()
                if (e.key === 'Enter' || e.key === 'Escape') setRenaming(false)
              }}
              onBlur={() => setRenaming(false)}
              autoFocus
              size="small"
              placeholder={testDefaultName(test, t)}
              slotProps={{ htmlInput: { maxLength: 100 } }}
              sx={{ flex: 1 }}
            />
          ) : (
            <>
              <Typography
                noWrap
                sx={{ flex: 1, cursor: 'pointer' }}
                onClick={() => onToggle(!expanded)}
              >
                {title}
              </Typography>
              {!isEditableType(test.type) && (
                <Chip size="small" label={t('tsl.rawChip')} variant="outlined" />
              )}
              {/* Worth surfacing on the collapsed row: a hidden test still runs and still counts
                  towards the grade, so its absence from the student's feedback is easy to forget
                  about and hard to spot otherwise. */}
              {!visibleToUserField(test) && (
                <Chip
                  size="small"
                  icon={<VisibilityOffOutlined />}
                  label={t('tsl.hiddenChip')}
                  variant="outlined"
                />
              )}
              {editing && (
                <Tooltip title={t('tsl.editTitle')}>
                  <IconButton
                    size="small"
                    onClick={(e) => {
                      e.stopPropagation()
                      setRenaming(true)
                    }}
                  >
                    <DriveFileRenameOutlineOutlined fontSize="small" />
                  </IconButton>
                </Tooltip>
              )}
            </>
          )}
          {editing && (
            <IconButton
              size="small"
              onClick={(e) => {
                e.stopPropagation()
                setMenuAnchor(e.currentTarget)
              }}
              aria-label={t('general.moreOptions')}
            >
              <MoreVertOutlined fontSize="small" />
            </IconButton>
          )}
        </Box>
      </Box>

      <Menu
        anchorEl={menuAnchor}
        open={!!menuAnchor}
        onClose={() => setMenuAnchor(null)}
        onClick={(e) => e.stopPropagation()}
      >
        <MenuItem
          onClick={() => {
            setMenuAnchor(null)
            actions.onDuplicate()
          }}
        >
          <ListItemIcon>
            <ContentCopyOutlined fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('general.duplicate')}</ListItemText>
        </MenuItem>
        <MenuItem
          disabled={index === 0}
          onClick={() => {
            setMenuAnchor(null)
            actions.onMove(-1)
          }}
        >
          <ListItemIcon>
            <ArrowUpwardOutlined fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('general.moveUp')}</ListItemText>
        </MenuItem>
        <MenuItem
          disabled={index === count - 1}
          onClick={() => {
            setMenuAnchor(null)
            actions.onMove(1)
          }}
        >
          <ListItemIcon>
            <ArrowDownwardOutlined fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('general.moveDown')}</ListItemText>
        </MenuItem>
        <MenuItem
          onClick={() => {
            setMenuAnchor(null)
            actions.onDelete()
          }}
        >
          <ListItemIcon>
            <DeleteOutlineOutlined fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('general.delete')}</ListItemText>
        </MenuItem>
      </Menu>

      <Collapse in={expanded} unmountOnExit>
        <Box p={2} borderTop={1} borderColor="divider">
          <Box display="flex" gap={2} alignItems="center" flexWrap="wrap">
            <FormControl size="small" sx={{ minWidth: 260, mb: 1 }} disabled={!editing}>
            <InputLabel id={typeLabelId}>{t('tsl.testType')}</InputLabel>
            <Select
              labelId={typeLabelId}
              label={t('tsl.testType')}
              value={test.type}
              onChange={(e) => changeType(e.target.value)}
            >
              {/* Grouped by whether the test runs the code or reads it — flattened with a
                  fragment because MUI's Select reads its children directly and will not descend
                  into a wrapper element to find the options. */}
              {TEST_TYPE_GROUPS.map((group) => [
                <ListSubheader key={group.labelKey}>{t(group.labelKey)}</ListSubheader>,
                ...group.types.map((type) => (
                  <MenuItem key={type} value={type}>
                    {defaultTestName(type, t)}
                  </MenuItem>
                )),
              ])}
              {/* Every type this editor knows has a form, so this only fires for a spec written
                  against one it does not. Kept selectable-but-disabled so the dropdown shows the
                  truth rather than silently reading as one of the types above. */}
              {!isEditableType(test.type) && (
                <MenuItem value={test.type} disabled>
                  {test.type}
                </MenuItem>
              )}
            </Select>
          </FormControl>

            {/* Points weight and visibility live on the base `Test` class, so every type has them
                and none of the bodies should. Both are written only when moved off their Kotlin
                default, which keeps specs to what was actually changed. */}
            <TextField
              label={t('tsl.pointsWeight')}
              type="number"
              value={pointsWeightField(test)}
              onChange={(e) =>
                actions.onChange(
                  setOrDefault(test, 'pointsWeight', Math.max(0, Number(e.target.value) || 0), 1),
                )
              }
              disabled={!editing}
              size="small"
              slotProps={{ htmlInput: { min: 0, step: 'any' } }}
              sx={{ width: 130, mb: 1 }}
            />
            <FormControlLabel
              sx={{ mb: 1 }}
              control={
                <Switch
                  checked={visibleToUserField(test)}
                  onChange={(e) =>
                    actions.onChange(setOrDefault(test, 'visibleToUser', e.target.checked, true))
                  }
                  disabled={!editing}
                  size="small"
                />
              }
              label={<Typography variant="body2">{t('tsl.visibleToUser')}</Typography>}
            />
          </Box>

          <TslTestBody test={test} editing={editing} onChange={actions.onChange} />
        </Box>
      </Collapse>
    </Paper>
  )
}
