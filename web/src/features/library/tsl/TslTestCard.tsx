import { useId, useState } from 'react'
import {
  Box,
  Chip,
  Collapse,
  FormControl,
  IconButton,
  InputLabel,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Paper,
  Select,
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
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import TslTestBody from './TslTestBody.tsx'
import { createTest, defaultTestName, isEditableType, TEST_TYPES, type TslTest } from './tslModel.ts'

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

  const title = test.name?.trim() ? test.name : defaultTestName(test.type, t)

  /**
   * Switching type replaces the body wholesale — the fields of one TSL test mean nothing to
   * another. The id and the name are what carry over.
   */
  function changeType(type: string) {
    if (!isEditableType(type)) return
    const fresh = createTest(type, test.id)
    const keptName = test.name?.trim() && test.name !== defaultTestName(test.type, t) ? test.name : null
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
              placeholder={defaultTestName(test.type, t)}
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
            <FormControl size="small" sx={{ minWidth: 260, mb: 1 }} disabled={!editing}>
            <InputLabel id={typeLabelId}>{t('tsl.testType')}</InputLabel>
            <Select
              labelId={typeLabelId}
              label={t('tsl.testType')}
              value={test.type}
              onChange={(e) => changeType(e.target.value)}
            >
              {TEST_TYPES.map((type) => (
                <MenuItem key={type} value={type}>
                  {defaultTestName(type, t)}
                </MenuItem>
              ))}
              {/* Keeps an unsupported type selectable so the dropdown shows the truth rather than
                  silently reading as one of the three implemented ones. */}
              {!isEditableType(test.type) && (
                <MenuItem value={test.type} disabled>
                  {test.type}
                </MenuItem>
              )}
            </Select>
          </FormControl>

          <TslTestBody test={test} editing={editing} onChange={actions.onChange} />
        </Box>
      </Collapse>
    </Paper>
  )
}
