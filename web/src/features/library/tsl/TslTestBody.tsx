import { useState } from 'react'
import { Alert, Box, TextField, Typography } from '@mui/material'
import { useTranslation } from 'react-i18next'
import CodeEditor from '../../../components/CodeEditor.tsx'
import {
  checkListField,
  fileListField,
  returnCheckField,
  strField,
  strListField,
  type TslTest,
} from './tslModel.ts'
import {
  TslDataChecksSection,
  TslGroupTitle,
  TslInputFilesSection,
  TslReturnCheckSection,
  TslStdInSection,
} from './TslSections.tsx'

interface BodyProps {
  test: TslTest
  editing: boolean
  onChange: (next: TslTest) => void
}

/**
 * Renders the form for one test. Every edit produces a *patched copy* of the incoming test, so
 * fields this UI doesn't show — and there are several on every type — survive the round trip.
 */
export default function TslTestBody({ test, editing, onChange }: BodyProps) {
  switch (test.type) {
    case 'placeholder_test':
      return <PlaceholderBody />
    case 'program_execution_test':
      return <ProgramExecutionBody test={test} editing={editing} onChange={onChange} />
    case 'function_execution_test':
      return <FunctionExecutionBody test={test} editing={editing} onChange={onChange} />
    default:
      return <RawBody test={test} editing={editing} onChange={onChange} />
  }
}

function PlaceholderBody() {
  const { t } = useTranslation()
  return (
    <Typography variant="body2" color="text.secondary">
      {t('tsl.placeholderHint')}
    </Typography>
  )
}

function ProgramExecutionBody({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  return (
    <Box>
      <TslGroupTitle>{t('tsl.inputs')}</TslGroupTitle>
      <TslStdInSection
        inputs={strListField(test, 'standardInputData')}
        editing={editing}
        onChange={(standardInputData) => onChange({ ...test, standardInputData })}
      />
      <TslInputFilesSection
        files={fileListField(test, 'inputFiles')}
        editing={editing}
        onChange={(inputFiles) => onChange({ ...test, inputFiles })}
      />

      <TslGroupTitle>{t('tsl.checks')}</TslGroupTitle>
      <TslDataChecksSection
        checks={checkListField(test, 'genericChecks')}
        editing={editing}
        onChange={(genericChecks) => onChange({ ...test, genericChecks })}
      />
    </Box>
  )
}

function FunctionExecutionBody({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  const functionName = strField(test, 'functionName')
  const args = strListField(test, 'arguments')

  return (
    <Box>
      <TextField
        label={t('tsl.functionName')}
        value={functionName}
        onChange={(e) => onChange({ ...test, functionName: e.target.value })}
        disabled={!editing}
        size="small"
        fullWidth
        required
        error={editing && functionName.trim() === ''}
        sx={{ '& input': { fontFamily: 'monospace' } }}
      />

      <TslGroupTitle>{t('tsl.inputs')}</TslGroupTitle>
      <TextField
        label={t('tsl.functionArgs')}
        value={args.join('\n')}
        onChange={(e) =>
          onChange({ ...test, arguments: e.target.value.split('\n').filter((a) => a.trim() !== '') })
        }
        disabled={!editing}
        multiline
        minRows={2}
        fullWidth
        size="small"
        helperText={t('tsl.functionArgsHelp')}
        sx={{ '& textarea': { fontFamily: 'monospace' } }}
      />
      <TslStdInSection
        inputs={strListField(test, 'standardInputData')}
        editing={editing}
        onChange={(standardInputData) => onChange({ ...test, standardInputData })}
      />
      <TslInputFilesSection
        files={fileListField(test, 'inputFiles')}
        editing={editing}
        onChange={(inputFiles) => onChange({ ...test, inputFiles })}
      />

      <TslGroupTitle>{t('tsl.checks')}</TslGroupTitle>
      <TslReturnCheckSection
        check={returnCheckField(test)}
        editing={editing}
        onChange={(returnValueCheck) => onChange({ ...test, returnValueCheck })}
      />
      <TslDataChecksSection
        checks={checkListField(test, 'genericChecks')}
        editing={editing}
        onChange={(genericChecks) => onChange({ ...test, genericChecks })}
      />
    </Box>
  )
}

/**
 * Fallback for the ~40 test types that don't have a form yet. Editing the JSON directly still
 * works, and leaving it alone preserves the test exactly — which is the point: an exercise
 * authored in wui must survive being opened here.
 */
function RawBody({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  // Local text, seeded once: re-serialising the parsed object back into the editor on every
  // keystroke would reformat whatever the user is halfway through typing and move their cursor.
  const [text, setText] = useState(() => JSON.stringify(test, null, 4))
  const [error, setError] = useState<string | null>(null)

  return (
    <Box>
      <Alert severity="info" sx={{ mb: 2 }}>
        {t('tsl.noFormForType', { type: test.type })}
      </Alert>
      <CodeEditor
        value={text}
        readOnly={!editing}
        minHeight="12rem"
        onChange={(next) => {
          setText(next)
          try {
            const parsed: unknown = JSON.parse(next)
            if (typeof parsed !== 'object' || parsed === null || typeof (parsed as TslTest).type !== 'string') {
              setError(t('tsl.rawNeedsType'))
              return
            }
            setError(null)
            onChange(parsed as TslTest)
          } catch (e) {
            setError((e as Error).message)
          }
        }}
      />
      {error && (
        <Typography variant="caption" color="error">
          {error}
        </Typography>
      )}
    </Box>
  )
}
