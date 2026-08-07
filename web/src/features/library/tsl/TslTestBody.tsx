import { useId, useState } from 'react'
import {
  Alert,
  Box,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  TextField,
  Typography,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import CodeEditor from '../../../components/CodeEditor.tsx'
import {
  checkListField,
  enumField,
  fileListField,
  genericCheckField,
  optStrField,
  returnCheckField,
  strField,
  strListField,
  type ContainsWhat,
  type Scope,
  type TargetType,
  type TslTest,
} from './tslModel.ts'
import {
  TslDataChecksSection,
  TslGroupTitle,
  TslInputFilesSection,
  TslReturnCheckSection,
  TslStdInSection,
} from './TslSections.tsx'
import { TslGenericCheckLongSection, TslScopeSection } from './TslStaticSections.tsx'

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
    case 'contains_test':
      return <ContainsBody test={test} editing={editing} onChange={onChange} />
    case 'calls_test':
      return <CallsBody test={test} editing={editing} onChange={onChange} />
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

const CONTAINS_WHAT: ContainsWhat[] = ['KEYWORD_NO_ARG', 'KEYWORD_WITH_PRECEDING_ARG', 'PHRASE']

/**
 * `contains_test` — one of the four types that replaced 39 (EZ-1607). This one alone stands in for
 * 13: every `{program,mainProgram,function,class}_contains_{keyword,phrase,loop,try_except,return}`
 * and `*_imports_module` test.
 *
 * The old boolean variants ("contains a loop", yes/no) have no special form here on purpose: they
 * are now just a keyword check whose expected values happen to be `for` / `while`, which is both
 * how the model expresses them and more flexible than the fixed pair ever was.
 */
function ContainsBody({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  const whatId = useId()
  const scope = enumField<Scope>(test, 'scope', 'PROGRAM')
  const containsWhat = enumField<ContainsWhat>(test, 'containsWhat', 'KEYWORD_NO_ARG')

  return (
    <Box>
      <TslGroupTitle>{t('tsl.whereToLook')}</TslGroupTitle>
      <TslScopeSection
        scope={scope}
        functionName={optStrField(test, 'functionName')}
        className={optStrField(test, 'className')}
        editing={editing}
        onChange={(patch) => onChange({ ...test, ...patch })}
      />

      <TslGroupTitle>{t('tsl.whatToLookFor')}</TslGroupTitle>
      <FormControl size="small" sx={{ minWidth: 260 }} disabled={!editing}>
        <InputLabel id={whatId}>{t('tsl.containsWhat')}</InputLabel>
        <Select
          labelId={whatId}
          label={t('tsl.containsWhat')}
          value={containsWhat}
          onChange={(e) => {
            const next = e.target.value as ContainsWhat
            // `import` is not a default the user may override — it is the only argument tiivad
            // accepts for this mode, so the UI owns the field rather than showing it.
            onChange({
              ...test,
              containsWhat: next,
              containsWhatArg: next === 'KEYWORD_WITH_PRECEDING_ARG' ? 'import' : null,
            })
          }}
        >
          {CONTAINS_WHAT.map((w) => (
            <MenuItem key={w} value={w}>
              {t(`tsl.containsWhatName.${w}`)}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      <TslGroupTitle>{t('tsl.checks')}</TslGroupTitle>
      <TslGenericCheckLongSection
        check={genericCheckField(test)}
        valuesLabel={t(`tsl.containsValuesLabel.${containsWhat}`)}
        valuesHelp={t(`tsl.containsValuesHelp.${containsWhat}`)}
        editing={editing}
        onChange={(genericCheck) => onChange({ ...test, genericCheck })}
      />
    </Box>
  )
}

const TARGET_TYPES: TargetType[] = ['FUNCTION', 'CLASS', 'CLASS_FUNCTION']

/**
 * `calls_test` — replaces 11 of the retired types, every `*_calls_*` combination.
 *
 * Same two sections as `ContainsBody`, which is the point of having built them: the only thing
 * that differs is *what* is being looked for. Note the two halves are independent — `scope` is
 * the caller and `targetType` the callee — so "a class method calls a function" is a scope of
 * CLASS with a target of FUNCTION, and all twelve combinations are legal.
 */
function CallsBody({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  const targetId = useId()
  const scope = enumField<Scope>(test, 'scope', 'PROGRAM')
  const targetType = enumField<TargetType>(test, 'targetType', 'FUNCTION')

  return (
    <Box>
      <TslGroupTitle>{t('tsl.whoCalls')}</TslGroupTitle>
      <TslScopeSection
        scope={scope}
        functionName={optStrField(test, 'functionName')}
        className={optStrField(test, 'className')}
        editing={editing}
        onChange={(patch) => onChange({ ...test, ...patch })}
      />

      <TslGroupTitle>{t('tsl.whatIsCalled')}</TslGroupTitle>
      <FormControl size="small" sx={{ minWidth: 260 }} disabled={!editing}>
        <InputLabel id={targetId}>{t('tsl.targetType')}</InputLabel>
        <Select
          labelId={targetId}
          label={t('tsl.targetType')}
          value={targetType}
          onChange={(e) => onChange({ ...test, targetType: e.target.value as TargetType })}
        >
          {TARGET_TYPES.map((tt) => (
            <MenuItem key={tt} value={tt}>
              {t(`tsl.targetTypeName.${tt}`)}
            </MenuItem>
          ))}
        </Select>
      </FormControl>

      <TslGroupTitle>{t('tsl.checks')}</TslGroupTitle>
      <TslGenericCheckLongSection
        check={genericCheckField(test)}
        valuesLabel={t(`tsl.callsValuesLabel.${targetType}`)}
        valuesHelp={t(`tsl.callsValuesHelp.${targetType}`)}
        editing={editing}
        onChange={(genericCheck) => onChange({ ...test, genericCheck })}
      />
    </Box>
  )
}

/**
 * Fallback for the test types that don't have a form yet. Editing the JSON directly still
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
