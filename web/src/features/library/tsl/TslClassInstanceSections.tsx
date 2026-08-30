/**
 * The check editor for `class_instance_test`, kept apart because it is the only nested one in the
 * model: a list of checks, each holding a list of expected fields. Nothing else in TSL has a
 * list inside a check, which is why this could not reuse `TslDataChecksSection`.
 *
 * What it compares: after the constructor code has run, tiivad reads `obj.__dict__` and matches it
 * against the listed fields (`ClassExecutionAnalyzer.fields_correct`). `checkName` and `checkValue`
 * are independent — with only names, the fields must exist holding anything; with only values, the
 * values must show up under some name or other; with both, each named field must hold its value.
 * `nothingElse` additionally forbids fields the check does not mention.
 */
import { Box, Button, Checkbox, FormControlLabel, IconButton, Paper, TextField, Typography } from '@mui/material'
import { AddOutlined, DeleteOutlineOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { TslFeedbackFields } from './TslSections.tsx'
import { emptyClassInstanceCheck, instanceCheckAsserts, type ClassInstanceCheck, type FieldData } from './tslModel.ts'

export function TslClassInstanceChecksSection({
  checks,
  editing,
  onChange,
}: {
  checks: ClassInstanceCheck[]
  editing: boolean
  onChange: (next: ClassInstanceCheck[]) => void
}) {
  const { t } = useTranslation()

  function patch(i: number, p: Partial<ClassInstanceCheck>) {
    onChange(checks.map((c, idx) => (idx === i ? { ...c, ...p } : c)))
  }

  function patchField(i: number, fi: number, p: Partial<FieldData>) {
    patch(i, { fieldsFinal: checks[i].fieldsFinal.map((f, idx) => (idx === fi ? { ...f, ...p } : f)) })
  }

  return (
    <Box>
      {checks.map((check, i) => (
        <Paper key={i} variant="outlined" sx={{ p: 2, mb: 2 }}>
          <Box display="flex" alignItems="center" justifyContent="space-between">
            <Typography variant="body2">{t('tsl.instanceFields')}</Typography>
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

          {check.fieldsFinal.map((field, fi) => (
            <Box key={fi} display="flex" alignItems="flex-start" gap={1} mt={1}>
              <TextField
                label={t('tsl.fieldName')}
                value={field.fieldName}
                onChange={(e) => patchField(i, fi, { fieldName: e.target.value })}
                disabled={!editing || !check.checkName}
                size="small"
                sx={{ maxWidth: 220, '& input': { fontFamily: 'monospace' } }}
              />
              <TextField
                label={t('tsl.fieldValue')}
                value={field.fieldContent}
                onChange={(e) => patchField(i, fi, { fieldContent: e.target.value })}
                disabled={!editing || !check.checkValue}
                size="small"
                // Emitted with forceString = false, so this reaches Python as written: 5 is a
                // number and text needs its own quotes. Saying so beats a puzzling failure.
                helperText={t('tsl.pythonLiteral')}
                sx={{ flex: 1, minWidth: 200, '& input': { fontFamily: 'monospace' } }}
              />
              {editing && (
                <IconButton
                  size="small"
                  onClick={() =>
                    patch(i, { fieldsFinal: check.fieldsFinal.filter((_, idx) => idx !== fi) })
                  }
                  aria-label={t('general.delete')}
                >
                  <DeleteOutlineOutlined fontSize="small" />
                </IconButton>
              )}
            </Box>
          ))}

          {editing && (
            <Button
              size="small"
              startIcon={<AddOutlined />}
              sx={{ mt: 1 }}
              onClick={() =>
                patch(i, { fieldsFinal: [...check.fieldsFinal, { fieldName: '', fieldContent: '' }] })
              }
            >
              {t('tsl.addField')}
            </Button>
          )}

          <Box display="flex" flexWrap="wrap" mt={1}>
            {(
              [
                ['checkName', 'tsl.checkFieldNames'],
                ['checkValue', 'tsl.checkFieldValues'],
                ['nothingElse', 'tsl.instanceNothingElse'],
              ] as const
            ).map(([key, label]) => (
              <FormControlLabel
                key={key}
                control={
                  <Checkbox
                    checked={check[key]}
                    onChange={(e) => patch(i, { [key]: e.target.checked })}
                    disabled={!editing}
                    size="small"
                  />
                }
                label={<Typography variant="body2">{t(label)}</Typography>}
              />
            ))}
          </Box>
          {/* A check that asserts nothing passes for everyone — cheap to say so, impossible to
              see otherwise. The shared predicate keeps this caption and the tab summary count in
              agreement; a lone nothingElse does not lift it, since with both compare boxes off
              the field inputs are disabled and the assertion has no expressible subject. */}
          {!instanceCheckAsserts(check) && (
            <Typography variant="caption" color="warning.main" display="block">
              {t('tsl.instanceChecksNothing')}
            </Typography>
          )}

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
              emptyClassInstanceCheck(t('tsl.instanceCheckPass'), t('tsl.instanceCheckFail')),
            ])
          }
        >
          {t('tsl.instanceCheck')}
        </Button>
      )}
    </Box>
  )
}
