/**
 * Shared form pieces for the *collapsed static tests* — `contains_test`, and (once they land)
 * `calls_test`, `definition_test`.
 *
 * Kept apart from `TslSections.tsx`, which serves the execution tests. The two families look
 * similar and are not: an execution test carries a *list* of `GenericCheck`, each with an id and
 * an output category; a static test carries exactly one `GenericCheckLong`, with neither, and a
 * wider quantifier. Sharing a component between them would mean a component that emits keys the
 * other side rejects — and kotlinx decodes with `ignoreUnknownKeys = false`, so that is a compile
 * error on save, not a warning.
 */
import { Box, Checkbox, FormControl, FormControlLabel, InputLabel, MenuItem, Paper, Select, TextField, Typography } from '@mui/material'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { TslFeedbackFields } from './TslSections.tsx'
import {
  quantifierUsesNothingElse,
  quantifierUsesValues,
  scopeNameField,
  type CheckTypeLong,
  type GenericCheckLong,
  type Scope,
} from './tslModel.ts'

const toLines = (values: string[]) => values.join('\n')
const fromLines = (text: string) => text.split('\n').filter((l) => l.trim() !== '')

const SCOPES: Scope[] = ['PROGRAM', 'MAIN_PROGRAM', 'FUNCTION', 'CLASS']

const CHECK_TYPE_LONG_KEYS: Record<CheckTypeLong, string> = {
  ALL_OF_THESE: 'tsl.longAll',
  ANY_OF_THESE: 'tsl.longAny',
  NONE_OF_THESE: 'tsl.longNone',
  MISSING_AT_LEAST_ONE_OF_THESE: 'tsl.longMissingOne',
  ANY: 'tsl.longAnyAtAll',
  NONE: 'tsl.longNoneAtAll',
}

/**
 * Where a static test looks: a scope, plus the name the scope implies.
 *
 * The conditional name field is not decoration. tiivad reads `scope_class_name` for CLASS and
 * `scope_function_name` for FUNCTION and constructs an analyzer from it; nothing validates that
 * server-side, so an empty one fails at grading time rather than at save time. Hence `required`
 * and the error state.
 */
export function TslScopeSection({
  scope,
  functionName,
  className,
  editing,
  onChange,
  scopeKey = 'scope',
}: {
  scope: Scope
  functionName: string
  className: string
  editing: boolean
  /** A partial test, already keyed correctly — spread it straight onto the test. */
  onChange: (patch: Record<string, unknown>) => void
  /**
   * Which field holds the scope. `contains_test` and `calls_test` call it `scope`;
   * `definition_test` calls the identical thing `scopeType` (EZ-1742). Rather than have the one
   * odd caller rewrite the patch — and get it subtly wrong when the value is absent — the
   * component emits the right key.
   */
  scopeKey?: 'scope' | 'scopeType'
}) {
  const { t } = useTranslation()
  const labelId = useId()
  const nameField = scopeNameField(scope)
  const nameValue = nameField === 'className' ? className : functionName

  return (
    <Box display="flex" gap={1} flexWrap="wrap" alignItems="flex-start">
      <FormControl size="small" sx={{ minWidth: 200 }} disabled={!editing}>
        <InputLabel id={labelId}>{t('tsl.scope')}</InputLabel>
        <Select
          labelId={labelId}
          label={t('tsl.scope')}
          value={scope}
          onChange={(e) => {
            // Clear the name that no longer applies, so switching PROGRAM→FUNCTION→PROGRAM does
            // not leave a stale functionName in the saved spec.
            const next = e.target.value as Scope
            const field = scopeNameField(next)
            onChange({
              [scopeKey]: next,
              functionName: field === 'functionName' ? functionName : null,
              className: field === 'className' ? className : null,
            })
          }}
        >
          {SCOPES.map((s) => (
            <MenuItem key={s} value={s}>
              {t(`tsl.scopeName.${s}`)}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      {nameField && (
        <TextField
          label={t(nameField === 'className' ? 'tsl.className' : 'tsl.functionName')}
          value={nameValue}
          onChange={(e) => onChange({ [nameField]: e.target.value })}
          disabled={!editing}
          size="small"
          required
          error={editing && nameValue.trim() === ''}
          sx={{ minWidth: 220, '& input': { fontFamily: 'monospace' } }}
        />
      )}
    </Box>
  )
}

/**
 * The single check on a collapsed static test.
 *
 * Two fields come and go with the quantifier, because tiivad ignores them otherwise:
 * `ANY`/`NONE` ask only whether the target set is non-empty, so the expected values are unread;
 * `nothingElse` is applied only to `ALL_OF_THESE`/`ANY_OF_THESE`. Both are *hidden rather than
 * cleared* — the value stays in the spec, so flipping the quantifier to `ANY` to try something
 * and back again doesn't silently eat what you typed.
 */
export function TslGenericCheckLongSection({
  check,
  valuesLabel,
  valuesHelp,
  editing,
  onChange,
}: {
  check: GenericCheckLong
  valuesLabel: string
  valuesHelp: string
  editing: boolean
  onChange: (next: GenericCheckLong) => void
}) {
  const { t } = useTranslation()
  const labelId = useId()
  const showValues = quantifierUsesValues(check.checkType)
  const showNothingElse = quantifierUsesNothingElse(check.checkType)

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <FormControl size="small" sx={{ minWidth: 260 }} disabled={!editing}>
        <InputLabel id={labelId}>{t('tsl.condition')}</InputLabel>
        <Select
          labelId={labelId}
          label={t('tsl.condition')}
          value={check.checkType}
          onChange={(e) => onChange({ ...check, checkType: e.target.value as CheckTypeLong })}
        >
          {Object.entries(CHECK_TYPE_LONG_KEYS).map(([value, key]) => (
            <MenuItem key={value} value={value}>
              {t(key)}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      {showValues && (
        <TextField
          label={valuesLabel}
          value={toLines(check.expectedValue ?? [])}
          onChange={(e) => onChange({ ...check, expectedValue: fromLines(e.target.value) })}
          disabled={!editing}
          multiline
          minRows={2}
          fullWidth
          size="small"
          sx={{ mt: 2, '& textarea': { fontFamily: 'monospace' } }}
          helperText={valuesHelp}
          error={editing && (check.expectedValue ?? []).length === 0}
        />
      )}

      {showNothingElse && (
        <FormControlLabel
          control={
            <Checkbox
              checked={check.nothingElse === true}
              onChange={(e) => onChange({ ...check, nothingElse: e.target.checked })}
              disabled={!editing}
              size="small"
            />
          }
          label={<Typography variant="body2">{t('tsl.nothingElse')}</Typography>}
        />
      )}

      <TslFeedbackFields
        passedMessage={check.passedMessage}
        failedMessage={check.failedMessage}
        editing={editing}
        onChange={(p) => onChange({ ...check, ...p })}
      />
    </Paper>
  )
}
