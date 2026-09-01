import {
  Box,
  Button,
  Checkbox,
  FormControlLabel,
  IconButton,
  MenuItem,
  Paper,
  Select,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import {
  AddOutlined,
  ArrowDownwardOutlined,
  ArrowUpwardOutlined,
  CheckOutlined,
  CloseOutlined,
  DeleteOutlineOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  emptyGenericCheck,
  emptyOutputFileCheck,
  emptyParamValueCheck,
  quantifierUsesNothingElse,
  type CheckType,
  type CheckTypeLong,
  type DataCategory,
  type ExceptionCheck,
  type FileData,
  type GenericCheck,
  type OutputFileCheck,
  type ParamValueCheck,
  type ReturnValueCheck,
} from './tslModel.ts'

/** Values in these fields are one-per-line, the same convention wui used. */
const toLines = (values: string[]) => values.join('\n')
const fromLines = (text: string) => text.split('\n').filter((l) => l.trim() !== '')

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <Typography variant="overline" color="text.secondary" display="block" sx={{ mt: 2 }}>
      {children}
    </Typography>
  )
}

/** The pass/fail feedback pair every check carries. Shared with the static-test sections. */
export function TslFeedbackFields({
  passedMessage,
  failedMessage,
  editing,
  onChange,
}: {
  passedMessage: string
  failedMessage: string
  editing: boolean
  onChange: (p: { passedMessage?: string; failedMessage?: string }) => void
}) {
  const { t } = useTranslation()
  return (
    <Box display="flex" flexDirection="column" gap={1} mt={1}>
      <Box display="flex" alignItems="center" gap={1}>
        <CheckOutlined fontSize="small" color="success" />
        <TextField
          value={passedMessage}
          onChange={(e) => onChange({ passedMessage: e.target.value })}
          disabled={!editing}
          size="small"
          fullWidth
          placeholder={t('tsl.feedbackPassed')}
        />
      </Box>
      <Box display="flex" alignItems="center" gap={1}>
        <CloseOutlined fontSize="small" color="error" />
        <TextField
          value={failedMessage}
          onChange={(e) => onChange({ failedMessage: e.target.value })}
          disabled={!editing}
          size="small"
          fullWidth
          placeholder={t('tsl.feedbackFailed')}
        />
      </Box>
    </Box>
  )
}

/**
 * The "and nothing else" checkbox, shared by every check section that carries the flag — three
 * copies drifted before this existed. Rendered only under the quantifiers tiivad applies it to
 * (X-019 / beta B13), and never on a check pinned to ALL_IO, where "nothing else" would count
 * the program's own input echo as something else and fail correct submissions. Hidden rather
 * than cleared when a gate closes, so flipping back does not eat the choice.
 */
export function TslNothingElseCheckbox({
  checkType,
  outputCategory,
  nothingElse,
  editing,
  onChange,
}: {
  checkType: CheckType | CheckTypeLong
  /** Absent on check shapes that have no such field (GenericCheckLong, OutputFileCheck). */
  outputCategory?: string
  nothingElse?: boolean | null
  editing: boolean
  onChange: (next: boolean) => void
}) {
  const { t } = useTranslation()
  if (!quantifierUsesNothingElse(checkType as CheckTypeLong)) return null
  if (outputCategory === 'ALL_IO') return null
  return (
    <FormControlLabel
      control={
        <Checkbox
          checked={nothingElse === true}
          onChange={(e) => onChange(e.target.checked)}
          disabled={!editing}
          size="small"
        />
      }
      label={<Typography variant="body2">{t('tsl.nothingElse')}</Typography>}
    />
  )
}

export function TslStdInSection({
  inputs,
  editing,
  onChange,
}: {
  inputs: string[]
  editing: boolean
  onChange: (next: string[]) => void
}) {
  const { t } = useTranslation()

  if (inputs.length === 0 && !editing) return null

  if (inputs.length === 0) {
    return (
      <Button size="small" startIcon={<AddOutlined />} onClick={() => onChange([''])}>
        {t('tsl.stdin')}
      </Button>
    )
  }

  return (
    // mb: the input-files button follows immediately, and sat flush against this box.
    <Box sx={{ mb: 2 }}>
      <TextField
        label={t('tsl.stdins')}
        value={toLines(inputs)}
        onChange={(e) => onChange(e.target.value.split('\n'))}
        disabled={!editing}
        multiline
        minRows={2}
        fullWidth
        size="small"
        helperText={t('tsl.stdinHelp')}
      />
    </Box>
  )
}

export function TslInputFilesSection({
  files,
  editing,
  onChange,
}: {
  files: FileData[]
  editing: boolean
  onChange: (next: FileData[]) => void
}) {
  const { t } = useTranslation()

  function patch(i: number, p: Partial<FileData>) {
    onChange(files.map((f, idx) => (idx === i ? { ...f, ...p } : f)))
  }

  return (
    <Box>
      {files.length > 0 && <SectionLabel>{t('tsl.inputFiles')}</SectionLabel>}
      {files.map((f, i) => (
        <Box key={i} mb={2}>
          <Box display="flex" alignItems="center" gap={1}>
            <TextField
              value={f.fileName}
              onChange={(e) => patch(i, { fileName: e.target.value })}
              disabled={!editing}
              size="small"
              placeholder="file.txt"
              label={t('tsl.inputFileName')}
              error={editing && f.fileName.trim() === ''}
              sx={{ maxWidth: 260 }}
            />
            {editing && (
              <IconButton
                size="small"
                onClick={() => onChange(files.filter((_, idx) => idx !== i))}
                aria-label={t('general.delete')}
              >
                <DeleteOutlineOutlined fontSize="small" />
              </IconButton>
            )}
          </Box>
          <TextField
            label={t('tsl.inputFileContent')}
            value={f.fileContent}
            onChange={(e) => patch(i, { fileContent: e.target.value })}
            disabled={!editing}
            multiline
            minRows={2}
            fullWidth
            size="small"
            sx={{ mt: 1 }}
          />
        </Box>
      ))}
      {editing && (
        <Button
          size="small"
          startIcon={<AddOutlined />}
          onClick={() => onChange([...files, { fileName: `file${files.length + 1}.txt`, fileContent: '' }])}
        >
          {t('tsl.inputFile')}
        </Button>
      )}
    </Box>
  )
}

export function TslReturnCheckSection({
  check,
  editing,
  onChange,
}: {
  check: ReturnValueCheck | null
  editing: boolean
  onChange: (next: ReturnValueCheck | null) => void
}) {
  const { t } = useTranslation()

  if (!check) {
    if (!editing) return null
    return (
      <Button
        size="small"
        startIcon={<AddOutlined />}
        onClick={() =>
          onChange({
            returnValue: '',
            beforeMessage: '',
            passedMessage: t('tsl.returnCheckPass'),
            failedMessage: t('tsl.returnCheckFail'),
          })
        }
      >
        {t('tsl.returnCheck')}
      </Button>
    )
  }

  return (
    <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
      <Box display="flex" alignItems="center" justifyContent="space-between">
        <Typography variant="body2">{t('tsl.returnCheckPrefix')}</Typography>
        {editing && (
          <IconButton size="small" onClick={() => onChange(null)} aria-label={t('general.delete')}>
            <DeleteOutlineOutlined fontSize="small" />
          </IconButton>
        )}
      </Box>
      <TextField
        label={t('tsl.returnValue')}
        value={check.returnValue}
        onChange={(e) => onChange({ ...check, returnValue: e.target.value })}
        disabled={!editing}
        size="small"
        fullWidth
        sx={{ mt: 1, '& input': { fontFamily: 'monospace' } }}
        helperText={t('tsl.returnCheckValueHelp')}
      />
      <TslFeedbackFields
        passedMessage={check.passedMessage}
        failedMessage={check.failedMessage}
        editing={editing}
        onChange={(p) => onChange({ ...check, ...p })}
      />
    </Paper>
  )
}

const CHECK_TYPE_KEYS: Record<CheckType, string> = {
  ALL_OF_THESE: 'tsl.containsAll',
  ANY_OF_THESE: 'tsl.containsOne',
  MISSING_AT_LEAST_ONE_OF_THESE: 'tsl.notContainsOne',
  NONE_OF_THESE: 'tsl.notContainsAll',
}

const DATA_CATEGORY_KEYS: Partial<Record<DataCategory, string>> = {
  CONTAINS_STRINGS: 'tsl.dataStrings',
  CONTAINS_NUMBERS: 'tsl.dataNumbers',
  CONTAINS_LINES: 'tsl.dataLines',
}

export function TslDataChecksSection({
  checks,
  editing,
  onChange,
}: {
  checks: GenericCheck[]
  editing: boolean
  onChange: (next: GenericCheck[]) => void
}) {
  const { t } = useTranslation()

  function patch(i: number, p: Partial<GenericCheck>) {
    onChange(checks.map((c, idx) => (idx === i ? { ...c, ...p } : c)))
  }

  function move(i: number, delta: number) {
    const next = [...checks]
    const [item] = next.splice(i, 1)
    next.splice(i + delta, 0, item)
    onChange(next)
  }

  return (
    <Box>
      {checks.map((check, i) => (
        <Paper key={check.id ?? i} variant="outlined" sx={{ p: 2, mb: 2 }}>
          <Box display="flex" alignItems="flex-start" justifyContent="space-between" gap={1}>
            <Box display="flex" alignItems="center" gap={1} flexWrap="wrap" flex={1}>
              <Typography variant="body2">{t('tsl.outputCheckSent1')}</Typography>
              <Select
                value={check.checkType}
                onChange={(e) => patch(i, { checkType: e.target.value as CheckType })}
                disabled={!editing}
                size="small"
                sx={{ minWidth: 190 }}
              >
                {Object.entries(CHECK_TYPE_KEYS).map(([value, key]) => (
                  <MenuItem key={value} value={value}>
                    {t(key)}
                  </MenuItem>
                ))}
              </Select>
              <Typography variant="body2">{t('tsl.outputCheckSent2')}</Typography>
              <Select
                value={check.dataCategory ?? 'CONTAINS_STRINGS'}
                onChange={(e) => patch(i, { dataCategory: e.target.value as DataCategory })}
                disabled={!editing}
                size="small"
                sx={{ minWidth: 140 }}
              >
                {Object.entries(DATA_CATEGORY_KEYS).map(([value, key]) => (
                  <MenuItem key={value} value={value}>
                    {t(key!)}
                  </MenuItem>
                ))}
              </Select>
            </Box>
            {editing && (
              <Box display="flex" flexShrink={0}>
                <Tooltip title={t('general.moveUp')}>
                  <span>
                    <IconButton size="small" disabled={i === 0} onClick={() => move(i, -1)} aria-label={t('general.moveUp')}>
                      <ArrowUpwardOutlined fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
                <Tooltip title={t('general.moveDown')}>
                  <span>
                    <IconButton size="small" disabled={i === checks.length - 1} onClick={() => move(i, 1)} aria-label={t('general.moveDown')}>
                      <ArrowDownwardOutlined fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
                <Tooltip title={t('general.remove')}>
                  <IconButton size="small" onClick={() => onChange(checks.filter((_, idx) => idx !== i))} aria-label={t('general.remove')}>
                    <DeleteOutlineOutlined fontSize="small" />
                  </IconButton>
                </Tooltip>
              </Box>
            )}
          </Box>

          <TextField
            // No visible label: the row above reads "Väljund <check> <category>" and this box is
            // the end of that sentence, so a label repeats in a box what the sentence already
            // said. The accessible name stays — the sentence is a visual arrangement, and without
            // this the field is an unnamed textarea to anyone not reading the layout.
            slotProps={{ htmlInput: { 'aria-label': t('tsl.expectedValues') } }}
            value={toLines(check.expectedValue ?? [])}
            onChange={(e) => patch(i, { expectedValue: fromLines(e.target.value) })}
            disabled={!editing}
            multiline
            minRows={2}
            fullWidth
            size="small"
            sx={{ mt: 1 }}
            helperText={t('tsl.expectedOutputs')}
          />

          <FormControlLabel
            control={
              <Checkbox
                checked={check.elementsOrdered === true}
                onChange={(e) => patch(i, { elementsOrdered: e.target.checked })}
                disabled={!editing}
                size="small"
              />
            }
            label={<Typography variant="body2">{t('tsl.ordered')}</Typography>}
          />
          <TslNothingElseCheckbox
            checkType={check.checkType}
            outputCategory={check.outputCategory}
            nothingElse={check.nothingElse}
            editing={editing}
            onChange={(nothingElse) => patch(i, { nothingElse })}
          />

          <TslFeedbackFields
            passedMessage={check.passedMessage}
            failedMessage={check.failedMessage}
            editing={editing}
            onChange={(p) => patch(i, p)}
          />
        </Paper>
      ))}
      {editing && (
        <Button
          size="small"
          startIcon={<AddOutlined />}
          onClick={() =>
            onChange([
              ...checks,
              emptyGenericCheck(t('tsl.outputCheckPass'), t('tsl.outputCheckFail')),
            ])
          }
        >
          {t('tsl.outputCheck')}
        </Button>
      )}
    </Box>
  )
}

/**
 * Checks against a file the submission wrote, rather than against its output. Same shape as a
 * data check plus the filename — a separate list on the Kotlin side, so a separate component.
 */
export function TslOutputFileChecksSection({
  checks,
  editing,
  onChange,
}: {
  checks: OutputFileCheck[]
  editing: boolean
  onChange: (next: OutputFileCheck[]) => void
}) {
  const { t } = useTranslation()

  function patch(i: number, p: Partial<OutputFileCheck>) {
    onChange(checks.map((c, idx) => (idx === i ? { ...c, ...p } : c)))
  }

  return (
    <Box>
      {checks.map((check, i) => (
        <Paper key={i} variant="outlined" sx={{ p: 2, mb: 2 }}>
          <Box display="flex" alignItems="center" gap={1} flexWrap="wrap">
            <TextField
              label={t('tsl.outputFileName')}
              value={check.fileName}
              onChange={(e) => patch(i, { fileName: e.target.value })}
              disabled={!editing}
              size="small"
              required
              error={editing && check.fileName.trim() === ''}
              sx={{ maxWidth: 220, '& input': { fontFamily: 'monospace' } }}
            />
            <Select
              value={check.checkType}
              onChange={(e) => patch(i, { checkType: e.target.value as CheckType })}
              disabled={!editing}
              size="small"
              sx={{ minWidth: 190 }}
            >
              {Object.entries(CHECK_TYPE_KEYS).map(([value, key]) => (
                <MenuItem key={value} value={value}>
                  {t(key)}
                </MenuItem>
              ))}
            </Select>
            <Select
              value={check.dataCategory ?? 'CONTAINS_STRINGS'}
              onChange={(e) => patch(i, { dataCategory: e.target.value as DataCategory })}
              disabled={!editing}
              size="small"
              sx={{ minWidth: 140 }}
            >
              {Object.entries(DATA_CATEGORY_KEYS).map(([value, key]) => (
                <MenuItem key={value} value={value}>
                  {t(key!)}
                </MenuItem>
              ))}
            </Select>
            <Box flex={1} />
            {editing && (
              <IconButton
                size="small"
                onClick={() => onChange(checks.filter((_, idx) => idx !== i))}
                aria-label={t('general.delete')}
              >
                <DeleteOutlineOutlined fontSize="small" />
              </IconButton>
            )}
          </Box>
          <TextField
            // No visible label: the row above reads "Väljund <check> <category>" and this box is
            // the end of that sentence, so a label repeats in a box what the sentence already
            // said. The accessible name stays — the sentence is a visual arrangement, and without
            // this the field is an unnamed textarea to anyone not reading the layout.
            slotProps={{ htmlInput: { 'aria-label': t('tsl.expectedValues') } }}
            value={toLines(check.expectedValue ?? [])}
            onChange={(e) => patch(i, { expectedValue: fromLines(e.target.value) })}
            disabled={!editing}
            multiline
            minRows={2}
            fullWidth
            size="small"
            sx={{ mt: 1 }}
            helperText={t('tsl.expectedFileContents')}
          />
          <FormControlLabel
            control={
              <Checkbox
                checked={check.elementsOrdered === true}
                onChange={(e) => patch(i, { elementsOrdered: e.target.checked })}
                disabled={!editing}
                size="small"
              />
            }
            label={<Typography variant="body2">{t('tsl.ordered')}</Typography>}
          />
          <TslNothingElseCheckbox
            checkType={check.checkType}
            nothingElse={check.nothingElse}
            editing={editing}
            onChange={(nothingElse) => patch(i, { nothingElse })}
          />
          <TslFeedbackFields
            passedMessage={check.passedMessage}
            failedMessage={check.failedMessage}
            editing={editing}
            onChange={(p) => patch(i, p)}
          />
        </Paper>
      ))}
      {editing && (
        <Button
          size="small"
          startIcon={<AddOutlined />}
          onClick={() =>
            onChange([
              ...checks,
              emptyOutputFileCheck(t('tsl.outputFileCheckPass'), t('tsl.outputFileCheckFail')),
            ])
          }
        >
          {t('tsl.outputFileCheck')}
        </Button>
      )}
    </Box>
  )
}

/** Whether the run may raise. Optional, like the return check. */
export function TslExceptionCheckSection({
  check,
  editing,
  onChange,
}: {
  check: ExceptionCheck | null
  editing: boolean
  onChange: (next: ExceptionCheck | null) => void
}) {
  const { t } = useTranslation()

  if (!check) {
    if (!editing) return null
    return (
      <Button
        size="small"
        startIcon={<AddOutlined />}
        onClick={() =>
          onChange({
            mustNotThrowException: true,
            beforeMessage: '',
            passedMessage: t('tsl.exceptionCheckPass'),
            failedMessage: t('tsl.exceptionCheckFail'),
          })
        }
      >
        {t('tsl.exceptionCheck')}
      </Button>
    )
  }

  return (
    <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
      <Box display="flex" alignItems="center" justifyContent="space-between" gap={1}>
        <FormControlLabel
          control={
            <Checkbox
              checked={check.mustNotThrowException}
              onChange={(e) => onChange({ ...check, mustNotThrowException: e.target.checked })}
              disabled={!editing}
              size="small"
            />
          }
          label={<Typography variant="body2">{t('tsl.mustNotThrow')}</Typography>}
        />
        {editing && (
          <IconButton size="small" onClick={() => onChange(null)} aria-label={t('general.delete')}>
            <DeleteOutlineOutlined fontSize="small" />
          </IconButton>
        )}
      </Box>
      <TslFeedbackFields
        passedMessage={check.passedMessage}
        failedMessage={check.failedMessage}
        editing={editing}
        onChange={(p) => onChange({ ...check, ...p })}
      />
    </Paper>
  )
}

/**
 * Checks an argument's value *after* the call — for functions that mutate what they are handed.
 *
 * `paramNumber` is a zero-based index, because tiivad uses it as one directly
 * (`self.arguments[param_number]`). Exposed as-is with the numbering spelled out in the label
 * rather than converted, since a silent ±1 between what the form shows and what the spec holds is
 * its own bug waiting to happen.
 */
export function TslParamValueChecksSection({
  checks,
  editing,
  onChange,
}: {
  checks: ParamValueCheck[]
  editing: boolean
  onChange: (next: ParamValueCheck[]) => void
}) {
  const { t } = useTranslation()

  function patch(i: number, p: Partial<ParamValueCheck>) {
    onChange(checks.map((c, idx) => (idx === i ? { ...c, ...p } : c)))
  }

  return (
    <Box>
      {checks.map((check, i) => (
        <Paper key={i} variant="outlined" sx={{ p: 2, mb: 2 }}>
          <Box display="flex" alignItems="center" gap={1} flexWrap="wrap">
            <TextField
              label={t('tsl.paramNumber')}
              type="number"
              value={check.paramNumber}
              onChange={(e) => patch(i, { paramNumber: Math.max(0, Number(e.target.value) || 0) })}
              disabled={!editing}
              size="small"
              slotProps={{ htmlInput: { min: 0 } }}
              helperText={t('tsl.paramNumberHelp')}
              sx={{ width: 150 }}
            />
            <TextField
              label={t('tsl.paramExpected')}
              value={check.expectedValue}
              onChange={(e) => patch(i, { expectedValue: e.target.value })}
              disabled={!editing}
              size="small"
              helperText={t('tsl.pythonLiteral')}
              sx={{ flex: 1, minWidth: 200, '& input': { fontFamily: 'monospace' } }}
            />
            {editing && (
              <IconButton
                size="small"
                onClick={() => onChange(checks.filter((_, idx) => idx !== i))}
                aria-label={t('general.delete')}
              >
                <DeleteOutlineOutlined fontSize="small" />
              </IconButton>
            )}
          </Box>
          <TslFeedbackFields
            passedMessage={check.passedMessage}
            failedMessage={check.failedMessage}
            editing={editing}
            onChange={(p) => patch(i, p)}
          />
        </Paper>
      ))}
      {editing && (
        <Button
          size="small"
          startIcon={<AddOutlined />}
          onClick={() =>
            onChange([...checks, emptyParamValueCheck(t('tsl.paramCheckPass'), t('tsl.paramCheckFail'))])
          }
        >
          {t('tsl.paramCheck')}
        </Button>
      )}
    </Box>
  )
}

/** Shared by the two implemented test bodies: the "Inputs" / "Checks" group headings. */
export function TslGroupTitle({ children }: { children: React.ReactNode }) {
  return (
    <Typography variant="subtitle2" sx={{ mt: 3, mb: 1, fontWeight: 600 }}>
      {children}
    </Typography>
  )
}
