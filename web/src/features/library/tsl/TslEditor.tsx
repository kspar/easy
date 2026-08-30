import { useEffect, useMemo, useState } from 'react'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  CircularProgress,
  ListSubheader,
  Menu,
  MenuItem,
  Tab,
  Tabs,
  Typography,
} from '@mui/material'
import { AddOutlined, ArrowDropDownOutlined, ExpandMoreOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { Extension } from '@codemirror/state'
import CodeEditor from '../../../components/CodeEditor.tsx'
import { languageFromFilename } from '../../course-exercise/editorLanguage.ts'
import TslTestCard from './TslTestCard.tsx'
import { useTslSpec } from './useTslSpec.ts'
import { duplicateTest, specTestProblems, type TslTest } from './tslModel.ts'
import { summarizeCompileError, summarizeParseError } from './tslErrors.ts'
import { PRESET_GROUPS } from './tslPresets.ts'

type TslTab = 'tests' | 'spec' | 'generated'

/** Loaded once; a fresh extension object per render would rebuild the editor on every keystroke. */
let jsonExtension: Extension | undefined

/**
 * The TSL auto-assessment editor: a visual test builder, the JSON spec, and the scripts the
 * compiler generates from it — the three tabs wui had, over a spec that stays in sync in both
 * directions (see `useTslSpec`).
 */
export default function TslEditor({
  value,
  editing,
  solutionFileName,
  onChange,
  onValidChange,
}: {
  /** Content of `tsl.json`. */
  value: string
  editing: boolean
  /** Seeds requiredFiles when the spec text is empty — see useTslSpec. */
  solutionFileName?: string
  onChange: (text: string) => void
  /** Reports whether the spec parses and compiles, so the page can gate Save. */
  onValidChange?: (valid: boolean) => void
}) {
  const { t } = useTranslation()
  const [tab, setTab] = useState<TslTab>('tests')
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const [lang, setLang] = useState<Extension | undefined>(jsonExtension)
  const [addAnchor, setAddAnchor] = useState<HTMLElement | null>(null)

  const store = useTslSpec({ value, onChange, solutionFileName })
  const { spec, parseError, compileFeedback, compileUnavailable, compiling, scripts, isValid } = store

  // Reports parse+compile validity only — the blank-required Save gate (audit X-027) is derived
  // by ExercisePage from the draft itself, so it holds even when this editor never mounts.
  useEffect(() => {
    onValidChange?.(isValid)
  }, [isValid, onValidChange])

  // The same counts the page gates on, surfaced where the teacher is looking. Warn tier
  // (checks nothing, audit X-023) never blocks; gate tier explains why Save is off.
  const problems = useMemo(() => specTestProblems(spec), [spec])

  useEffect(() => {
    if (jsonExtension) return
    let cancelled = false
    // JS mode highlights JSON well enough and is already a dependency, unlike lang-json.
    import('@codemirror/lang-javascript').then((m) => {
      jsonExtension = m.javascript()
      if (!cancelled) setLang(jsonExtension)
    })
    return () => {
      cancelled = true
    }
  }, [])

  function setTests(tests: TslTest[]) {
    store.setFromModel({ ...spec, tests })
  }

  const tests = spec.tests

  return (
    <Box>
      {/* One teacher-voiced sentence, raw diagnostics behind a disclosure (audit X-018): what
          used to render here verbatim was kotlinx's own developer advice — in English, ending in
          the teacher's whole document echoed back. */}
      {parseError ? (
        <TslErrorAlert {...summarizeParseError(parseError)} raw={parseError} />
      ) : compileFeedback ? (
        <TslErrorAlert {...summarizeCompileError(compileFeedback)} raw={compileFeedback} />
      ) : compileUnavailable ? (
        <TslErrorAlert messageKey="tsl.errorCompileUnavailable" raw={compileUnavailable} />
      ) : null}

      <Box display="flex" alignItems="center" gap={1}>
        <Tabs
          value={tab}
          onChange={(_, v) => setTab(v)}
          sx={{ minHeight: 36, flex: 1, '& .MuiTab-root': { minHeight: 36, textTransform: 'none' } }}
        >
          <Tab value="tests" label={t('tsl.tabTests')} />
          <Tab value="spec" label={t('tsl.tabSpec')} />
          <Tab value="generated" label={t('tsl.tabGenerated')} />
        </Tabs>
        {compiling && <CircularProgress size={16} />}
      </Box>

      {/* Hidden while the JSON does not parse: `spec` is then the last good one, and counting
          its tests next to a parse error about different text describes two different specs. */}
      {editing && !parseError && problems.blankRequired > 0 && (
        <Typography variant="caption" color="error.main" display="block" mt={1}>
          {t('tsl.blankRequiredSummary', { count: problems.blankRequired })}
        </Typography>
      )}
      {editing && !parseError && problems.checksNothing > 0 && (
        <Typography variant="caption" color="warning.main" display="block" mt={1}>
          {t('tsl.checksNothingSummary', { count: problems.checksNothing })}
        </Typography>
      )}

      <Box mt={2}>
        {tab === 'tests' && (
          <>
            {tests.length === 0 && (
              <Typography color="text.secondary" variant="body2" mb={2}>
                {t('tsl.noTests')}
              </Typography>
            )}
            {tests.map((test, i) => (
              <TslTestCard
                key={test.id}
                test={test}
                index={i}
                count={tests.length}
                editing={editing}
                expanded={expanded.has(test.id)}
                onToggle={(open) =>
                  setExpanded((prev) => {
                    const next = new Set(prev)
                    if (open) next.add(test.id)
                    else next.delete(test.id)
                    return next
                  })
                }
                actions={{
                  onChange: (next) => setTests(tests.map((x, idx) => (idx === i ? next : x))),
                  onDuplicate: () => {
                    const copy = duplicateTest(test, t('tsl.copySuffix'))
                    setTests([...tests.slice(0, i + 1), copy, ...tests.slice(i + 1)])
                  },
                  onDelete: () => setTests(tests.filter((_, idx) => idx !== i)),
                  onMove: (delta) => {
                    const next = [...tests]
                    const [item] = next.splice(i, 1)
                    next.splice(i + delta, 0, item)
                    setTests(next)
                  },
                }}
              />
            ))}
            {editing && (
              <>
                <Button
                  startIcon={<AddOutlined />}
                  endIcon={<ArrowDropDownOutlined />}
                  onClick={(e) => setAddAnchor(e.currentTarget)}
                  aria-haspopup="menu"
                >
                  {t('tsl.addTest')}
                </Button>
                <Menu anchorEl={addAnchor} open={!!addAnchor} onClose={() => setAddAnchor(null)}>
                  {/* Flattened rather than wrapped: MUI's Menu, like Select, reads its children
                      directly and will not look inside a container for the items. */}
                  {PRESET_GROUPS.map((group) => [
                    <ListSubheader key={group.labelKey}>{t(group.labelKey)}</ListSubheader>,
                    ...group.presets.map((preset) => (
                      <MenuItem
                        key={preset.id}
                        onClick={() => {
                          setAddAnchor(null)
                          const test = preset.build(t)
                          setTests([...tests, test])
                          setExpanded((prev) => new Set(prev).add(test.id))
                        }}
                      >
                        {t(`tsl.preset.${preset.id}`)}
                      </MenuItem>
                    )),
                  ])}
                </Menu>
              </>
            )}
          </>
        )}

        {tab === 'spec' && (
          <CodeEditor
            value={store.text}
            onChange={store.setFromText}
            language={lang}
            readOnly={!editing}
            minHeight="26rem"
          />
        )}

        {tab === 'generated' && <GeneratedScripts scripts={scripts} />}
      </Box>
    </Box>
  )
}

function TslErrorAlert({
  messageKey,
  params,
  raw,
}: {
  messageKey: string
  params?: Record<string, string>
  raw: string
}) {
  const { t } = useTranslation()
  return (
    <Alert severity="error" sx={{ mb: 2 }}>
      <Typography variant="body2">{t(messageKey, params)}</Typography>
      {/* Kept, not deleted: the raw diagnostic is what a bug report or a colleague needs.
          Keyed by the text so a *different* error starts collapsed again — an open disclosure
          carrying over would present the new raw dump as if it were the summary. */}
      <Accordion
        key={raw}
        disableGutters
        elevation={0}
        // Both halves of BugReportDialog's debugged dark-mode fix: Paper paints its dark-mode
        // elevation overlay as a background *image*, which bgcolor alone does not clear.
        sx={{ mt: 0.5, bgcolor: 'transparent', backgroundImage: 'none', '&:before': { display: 'none' } }}
      >
        <AccordionSummary expandIcon={<ExpandMoreOutlined />} sx={{ minHeight: 32, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}>
          <Typography variant="caption" color="text.secondary">
            {t('tsl.errorDetails')}
          </Typography>
        </AccordionSummary>
        <AccordionDetails sx={{ px: 0, pt: 0 }}>
          <Typography
            variant="body2"
            component="pre"
            sx={{ m: 0, whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: '0.75rem' }}
          >
            {raw}
          </Typography>
        </AccordionDetails>
      </Accordion>
    </Alert>
  )
}

function GeneratedScripts({ scripts }: { scripts: Record<string, string> }) {
  const { t } = useTranslation()
  const names = Object.keys(scripts).sort()
  const [active, setActive] = useState<string | null>(null)
  const shown = active && scripts[active] !== undefined ? active : names[0]
  const [lang, setLang] = useState<Extension | undefined>(undefined)

  useEffect(() => {
    if (!shown) return
    let cancelled = false
    languageFromFilename(shown).then((l) => {
      if (!cancelled) setLang(l)
    })
    return () => {
      cancelled = true
    }
  }, [shown])

  if (names.length === 0) {
    return (
      <Typography color="text.secondary" variant="body2">
        {t('tsl.noGeneratedScripts')}
      </Typography>
    )
  }

  return (
    <Box>
      <Tabs
        value={shown}
        onChange={(_, v) => setActive(v)}
        variant="scrollable"
        scrollButtons="auto"
        sx={{ minHeight: 36, mb: 1, '& .MuiTab-root': { minHeight: 36, textTransform: 'none' } }}
      >
        {names.map((n) => (
          <Tab key={n} value={n} label={n} />
        ))}
      </Tabs>
      <CodeEditor key={shown} value={scripts[shown] ?? ''} readOnly language={lang} minHeight="22rem" />
    </Box>
  )
}
