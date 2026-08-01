import { useEffect, useState } from 'react'
import { Alert, Box, Button, CircularProgress, Tab, Tabs, Typography } from '@mui/material'
import { AddOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { Extension } from '@codemirror/state'
import CodeEditor from '../../../components/CodeEditor.tsx'
import { languageFromFilename } from '../../course-exercise/editorLanguage.ts'
import TslTestCard from './TslTestCard.tsx'
import { useTslSpec } from './useTslSpec.ts'
import { createTest, duplicateTest, type TslTest } from './tslModel.ts'

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
  onChange,
  onValidChange,
}: {
  /** Content of `tsl.json`. */
  value: string
  editing: boolean
  onChange: (text: string) => void
  /** Reports whether the spec parses and compiles, so the page can gate Save. */
  onValidChange?: (valid: boolean) => void
}) {
  const { t } = useTranslation()
  const [tab, setTab] = useState<TslTab>('tests')
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const [lang, setLang] = useState<Extension | undefined>(jsonExtension)

  const store = useTslSpec({ value, onChange })
  const { spec, parseError, compileFeedback, compiling, scripts, isValid } = store

  useEffect(() => {
    onValidChange?.(isValid)
  }, [isValid, onValidChange])

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
      {parseError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          <Typography variant="body2" component="pre" sx={{ m: 0, whiteSpace: 'pre-wrap' }}>
            {parseError}
          </Typography>
        </Alert>
      )}
      {!parseError && compileFeedback && (
        <Alert severity="error" sx={{ mb: 2 }}>
          <Typography variant="body2" component="pre" sx={{ m: 0, whiteSpace: 'pre-wrap' }}>
            {compileFeedback}
          </Typography>
        </Alert>
      )}

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
              <Button
                startIcon={<AddOutlined />}
                onClick={() => {
                  const test = createTest('placeholder_test')
                  setTests([...tests, test])
                  setExpanded((prev) => new Set(prev).add(test.id))
                }}
              >
                {t('tsl.addTest')}
              </Button>
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
