import { useId, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Collapse,
  FormControl,
  FormControlLabel,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Switch,
  TextField,
  Typography,
} from '@mui/material'
import { ExpandMoreOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import CodeEditor from '../../../components/CodeEditor.tsx'
import {
  checkListField,
  enumField,
  exceptionCheckField,
  fileListField,
  genericCheckField,
  instanceChecksField,
  optStrField,
  outputFileChecksField,
  paramChecksField,
  propertyCheckField,
  returnCheckField,
  setOrUnset,
  strField,
  strListField,
  type ContainsWhat,
  type DefinitionCheckType,
  type FunctionProperty,
  type FunctionType,
  type Scope,
  type TargetType,
  type TslTest,
} from './tslModel.ts'
import {
  TslDataChecksSection,
  TslExceptionCheckSection,
  TslFeedbackFields,
  TslGroupTitle,
  TslInputFilesSection,
  TslOutputFileChecksSection,
  TslParamValueChecksSection,
  TslReturnCheckSection,
  TslStdInSection,
} from './TslSections.tsx'
import { TslGenericCheckLongSection, TslScopeSection } from './TslStaticSections.tsx'
import { TslClassInstanceChecksSection } from './TslClassInstanceSections.tsx'

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
    case 'class_instance_test':
      return <ClassInstanceBody test={test} editing={editing} onChange={onChange} />
    case 'contains_test':
      return <ContainsBody test={test} editing={editing} onChange={onChange} />
    case 'calls_test':
      return <CallsBody test={test} editing={editing} onChange={onChange} />
    case 'definition_test':
      return <DefinitionBody test={test} editing={editing} onChange={onChange} />
    case 'function_is_test':
      return <FunctionIsBody test={test} editing={editing} onChange={onChange} />
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
      <TslOutputFileChecksSection
        checks={outputFileChecksField(test)}
        editing={editing}
        onChange={(outputFileChecks) => onChange({ ...test, outputFileChecks })}
      />
      <TslExceptionCheckSection
        check={exceptionCheckField(test)}
        editing={editing}
        onChange={(exceptionCheck) => onChange({ ...test, exceptionCheck })}
      />
    </Box>
  )
}

function FunctionExecutionBody({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  const typeId = useId()
  const functionName = strField(test, 'functionName')
  const args = strListField(test, 'arguments')
  const functionType = enumField<FunctionType>(test, 'functionType', 'FUNCTION')

  return (
    <Box>
      <Box display="flex" gap={1} flexWrap="wrap">
        <TextField
          label={t('tsl.functionName')}
          value={functionName}
          onChange={(e) => onChange({ ...test, functionName: e.target.value })}
          disabled={!editing}
          size="small"
          required
          error={editing && functionName.trim() === ''}
          sx={{ flex: 1, minWidth: 240, '& input': { fontFamily: 'monospace' } }}
        />
        <FormControl size="small" sx={{ minWidth: 200 }} disabled={!editing}>
          <InputLabel id={typeId}>{t('tsl.functionKind')}</InputLabel>
          <Select
            labelId={typeId}
            label={t('tsl.functionKind')}
            value={functionType}
            onChange={(e) =>
              // A plain function has no object to be called on, so drop the constructor code
              // rather than leave it to be sent and ignored.
              onChange({
                ...test,
                functionType: e.target.value as FunctionType,
                createObject: e.target.value === 'METHOD' ? optStrField(test, 'createObject') : null,
              })
            }
          >
            {(['FUNCTION', 'METHOD'] as FunctionType[]).map((ft) => (
              <MenuItem key={ft} value={ft}>
                {t(`tsl.functionKindName.${ft}`)}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
      </Box>

      {functionType === 'METHOD' && (
        <Box mt={2}>
          <Typography variant="body2" gutterBottom>
            {t('tsl.createObject')}
          </Typography>
          <CodeEditor
            ariaLabel={t('tsl.createObject')}
            value={optStrField(test, 'createObject')}
            readOnly={!editing}
            minHeight="5rem"
            onChange={(createObject) => onChange({ ...test, createObject })}
          />
          <Typography variant="caption" color="text.secondary">
            {t('tsl.createObjectHelp')}
          </Typography>
        </Box>
      )}

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
      <TslParamValueChecksSection
        checks={paramChecksField(test)}
        editing={editing}
        onChange={(paramValueChecks) => onChange({ ...test, paramValueChecks })}
      />
      <TslOutputFileChecksSection
        checks={outputFileChecksField(test)}
        editing={editing}
        onChange={(outputFileChecks) => onChange({ ...test, outputFileChecks })}
      />

      <TslErrorMessagesSection test={test} editing={editing} onChange={onChange} />
    </Box>
  )
}

/**
 * `function_execution_test`'s three overridable error messages.
 *
 * Collapsed by default because they are rarely touched, and written through `setOrUnset` because
 * each has a non-empty Kotlin default: clearing the box has to remove the key, not save `""`,
 * or the student sees no message at all where they used to see the default one. Placeholders show
 * what the default actually is.
 */
function TslErrorMessagesSection({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const fields = [
    ['outOfInputsErrorMsg', 'tsl.errOutOfInputs'],
    ['functionNotDefinedErrorMsg', 'tsl.errNotDefined'],
    ['tooManyArgumentsProvidedErrorMsg', 'tsl.errTooManyArgs'],
  ] as const
  const overridden = fields.filter(([key]) => optStrField(test, key).trim() !== '').length

  return (
    <Box mt={1}>
      <Button size="small" onClick={() => setOpen(!open)} endIcon={<ExpandMoreOutlined
        sx={{ transform: open ? 'rotate(180deg)' : 'none', transition: '.15s' }} />}>
        {t('tsl.errorMessages')}
        {overridden > 0 && ` (${overridden})`}
      </Button>
      <Collapse in={open} unmountOnExit>
        <Box display="flex" flexDirection="column" gap={2} mt={1}>
          {fields.map(([key, label]) => (
            <TextField
              key={key}
              label={t(label)}
              value={optStrField(test, key)}
              onChange={(e) => onChange(setOrUnset(test, key, e.target.value))}
              disabled={!editing}
              size="small"
              fullWidth
              placeholder={t(`${label}Default`)}
              helperText={t('tsl.errorMessageHelp')}
            />
          ))}
        </Box>
      </Collapse>
    </Box>
  )
}

/**
 * `class_instance_test` — build an object, then check the state it ended up in.
 *
 * The one type the collapse left alone, and the only one with a nested check structure. Its
 * `className` is required by the model but read by nothing: tiivad recovers the class from the
 * constructor code by regex (EZ-1742). Shown anyway — unlike `definitionCheckValue` it is not a
 * second copy of something already on the form, and it is how a teacher names what they are
 * testing.
 */
function ClassInstanceBody({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  const className = strField(test, 'className')

  return (
    <Box>
      <TextField
        label={t('tsl.className')}
        value={className}
        onChange={(e) => onChange({ ...test, className: e.target.value })}
        disabled={!editing}
        size="small"
        fullWidth
        required
        error={editing && className.trim() === ''}
        sx={{ '& input': { fontFamily: 'monospace' } }}
      />

      <TslGroupTitle>{t('tsl.createObject')}</TslGroupTitle>
      {/* A code body, not an expression: tiivad indents it into `def create_object_fun_auto_assess()`,
          so it must `return` the instance. A one-line field would misrepresent that. */}
      <CodeEditor
        ariaLabel={t('tsl.createObject')}
        value={strField(test, 'createObject')}
        readOnly={!editing}
        minHeight="6rem"
        onChange={(createObject) => onChange({ ...test, createObject })}
      />
      <Typography variant="caption" color="text.secondary">
        {t('tsl.createObjectHelp')}
      </Typography>

      <TslGroupTitle>{t('tsl.checks')}</TslGroupTitle>
      <TslClassInstanceChecksSection
        checks={instanceChecksField(test)}
        editing={editing}
        onChange={(classInstanceChecks) => onChange({ ...test, classInstanceChecks })}
      />
      <TslDataChecksSection
        checks={checkListField(test, 'genericChecks')}
        editing={editing}
        onChange={(genericChecks) => onChange({ ...test, genericChecks })}
      />
      <TslOutputFileChecksSection
        checks={outputFileChecksField(test)}
        editing={editing}
        onChange={(outputFileChecks) => onChange({ ...test, outputFileChecks })}
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

const DEFINITION_KINDS: DefinitionCheckType[] = ['FUNCTION', 'CLASS']

/**
 * `definition_test` — replaces the five `*_defines_*` types, plus `class_is_subclass_test`, which
 * is now expressed as a `superClassName` rather than a type of its own.
 *
 * Two model quirks are absorbed here rather than shown to the teacher:
 *
 *  - the scope lives in `scopeType`, not `scope`, unlike its two siblings (EZ-1742);
 *  - `definitionCheckValue` is required and non-null but read by nothing — not the compiler's
 *    output, not tiivad. The names actually checked come from the check's expected values. So it
 *    is kept in sync with the first of those instead of being a second field asking for the same
 *    thing. It still feeds Kotlin's `getDefaultName()`, so it cannot simply be left blank.
 */
function DefinitionBody({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  const kindId = useId()
  const scope = enumField<Scope>(test, 'scopeType', 'PROGRAM')
  const kind = enumField<DefinitionCheckType>(test, 'definitionCheckType', 'FUNCTION')
  const check = genericCheckField(test)

  return (
    <Box>
      <TslGroupTitle>{t('tsl.whereToLook')}</TslGroupTitle>
      <TslScopeSection
        scope={scope}
        scopeKey="scopeType"
        functionName={optStrField(test, 'functionName')}
        className={optStrField(test, 'className')}
        editing={editing}
        onChange={(patch) => onChange({ ...test, ...patch })}
      />

      <TslGroupTitle>{t('tsl.whatIsDefined')}</TslGroupTitle>
      <Box display="flex" gap={1} flexWrap="wrap">
        <FormControl size="small" sx={{ minWidth: 260 }} disabled={!editing}>
          <InputLabel id={kindId}>{t('tsl.definitionKind')}</InputLabel>
          <Select
            labelId={kindId}
            label={t('tsl.definitionKind')}
            value={kind}
            onChange={(e) =>
              // Clearing superClassName is not tidiness: tiivad raises outright when it arrives
              // alongside definition_check_type=FUNCTION.
              onChange({
                ...test,
                definitionCheckType: e.target.value as DefinitionCheckType,
                superClassName: e.target.value === 'CLASS' ? optStrField(test, 'superClassName') || null : null,
              })
            }
          >
            {DEFINITION_KINDS.map((k) => (
              <MenuItem key={k} value={k}>
                {t(`tsl.definitionKindName.${k}`)}
              </MenuItem>
            ))}
          </Select>
        </FormControl>

        {kind === 'CLASS' && (
          <TextField
            label={t('tsl.superClassName')}
            value={optStrField(test, 'superClassName')}
            onChange={(e) => onChange({ ...test, superClassName: e.target.value || null })}
            disabled={!editing}
            size="small"
            helperText={t('tsl.superClassHelp')}
            sx={{ minWidth: 240, '& input': { fontFamily: 'monospace' } }}
          />
        )}
      </Box>

      <TslGroupTitle>{t('tsl.checks')}</TslGroupTitle>
      <TslGenericCheckLongSection
        check={check}
        valuesLabel={t(`tsl.definesValuesLabel.${kind}`)}
        valuesHelp={t(`tsl.definesValuesHelp.${kind}`)}
        editing={editing}
        onChange={(genericCheck) =>
          onChange({
            ...test,
            genericCheck,
            definitionCheckValue: genericCheck.expectedValue[0] ?? '',
          })
        }
      />
    </Box>
  )
}

const FUNCTION_PROPERTIES: FunctionProperty[] = ['RECURSIVE', 'PURE']

/**
 * `function_is_test` — the two `function_is_*` types.
 *
 * The odd one out of the four: no scope, and no `GenericCheckLong`. `is_pure()` / `is_recursive()`
 * return a bool rather than a set, so there is nothing to quantify over and the whole condition is
 * one `mustHaveProperty` switch.
 *
 * The helper text is not padding. tiivad documents real analyser limitations — mutual recursion
 * isn't detected, and several ways of touching a module-level name still count as pure — and a
 * teacher who doesn't know that will write a test that passes work it shouldn't.
 */
function FunctionIsBody({ test, editing, onChange }: BodyProps) {
  const { t } = useTranslation()
  const propId = useId()
  const functionName = strField(test, 'functionName')
  const property = enumField<FunctionProperty>(test, 'functionProperty', 'RECURSIVE')
  const check = propertyCheckField(test)

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

      <TslGroupTitle>{t('tsl.checks')}</TslGroupTitle>
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Box display="flex" gap={1} flexWrap="wrap" alignItems="center">
          <FormControl size="small" sx={{ minWidth: 220 }} disabled={!editing}>
            <InputLabel id={propId}>{t('tsl.functionProperty')}</InputLabel>
            <Select
              labelId={propId}
              label={t('tsl.functionProperty')}
              value={property}
              onChange={(e) => onChange({ ...test, functionProperty: e.target.value as FunctionProperty })}
            >
              {FUNCTION_PROPERTIES.map((p) => (
                <MenuItem key={p} value={p}>
                  {t(`tsl.functionPropertyName.${p}`)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <FormControlLabel
            control={
              <Switch
                checked={check.mustHaveProperty}
                onChange={(e) => onChange({ ...test, propertyCheck: { ...check, mustHaveProperty: e.target.checked } })}
                disabled={!editing}
                size="small"
              />
            }
            label={
              <Typography variant="body2">
                {t(check.mustHaveProperty ? 'tsl.mustHaveProperty' : 'tsl.mustNotHaveProperty')}
              </Typography>
            }
          />
        </Box>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
          {t(`tsl.functionPropertyHint.${property}`)}
        </Typography>
        <TslFeedbackFields
          passedMessage={check.passedMessage}
          failedMessage={check.failedMessage}
          editing={editing}
          onChange={(p) => onChange({ ...test, propertyCheck: { ...check, ...p } })}
        />
      </Paper>
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
        ariaLabel={t('tsl.rawChip')}
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
