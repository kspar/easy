import { Fragment, useEffect, useId, useMemo, useState } from 'react'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  FormControl,
  IconButton,
  InputLabel,
  MenuItem,
  Select,
  Tab,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { AddOutlined, DeleteOutlineOutlined, ExpandMoreOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { Extension } from '@codemirror/state'
import CodeEditor from '../../components/CodeEditor.tsx'
import { languageFromFilename } from '../course-exercise/editorLanguage.ts'
import { AUTO_EVAL_TYPES, autoEvalTypeOf, isTslContainer } from './autoEvalTypes.ts'
import { assetsToMap, mapToAssets, TSL_SPEC_FILENAME, type AutoAssessDraft } from './exerciseDraft.ts'
import TslEditor from './tsl/TslEditor.tsx'
import { emptySpec, parseSpec, serializeSpec } from './tsl/tslModel.ts'

const EVAL_SCRIPT_TAB = '__eval__'

export default function AutoAssessTab({
  draft,
  editing,
  onChange,
  onTslValidChange,
}: {
  draft: AutoAssessDraft
  editing: boolean
  onChange: (next: AutoAssessDraft) => void
  /** The TSL spec's own validity — it can't be derived from the draft, only from the compiler. */
  onTslValidChange?: (valid: boolean) => void
}) {
  const { t } = useTranslation()
  const [selectedFile, setActiveFile] = useState<string>(EVAL_SCRIPT_TAB)
  // MUI needs explicit ids to associate an InputLabel with a Select; see TslTestCard.
  const evalTypeLabelId = useId()
  const [lang, setLang] = useState<Extension | undefined>(undefined)

  const hasAuto = draft.containerImage !== null
  const type = autoEvalTypeOf(draft.containerImage)
  // TSL exercises are authored through the visual builder; everything else edits script files.
  const usesTslEditor = isTslContainer(draft.containerImage)
  const filesEditable = editing

  // A container the templates don't know about — keep it selectable so saving doesn't silently
  // rewrite someone's hand-set image.
  const unknownContainer = hasAuto && !type ? draft.containerImage : null

  // Derived rather than corrected in an effect: a type switch can drop the asset that was open,
  // and falling back here keeps the Tabs value pointing at a tab that actually exists.
  const activeFile =
    selectedFile !== EVAL_SCRIPT_TAB && draft.assets.every((a) => a.file_name !== selectedFile)
      ? EVAL_SCRIPT_TAB
      : selectedFile

  const activeContent =
    activeFile === EVAL_SCRIPT_TAB
      ? draft.gradingScript
      : (draft.assets.find((a) => a.file_name === activeFile)?.file_content ?? '')

  useEffect(() => {
    let cancelled = false
    const filename = activeFile === EVAL_SCRIPT_TAB ? 'evaluate.sh' : activeFile
    languageFromFilename(filename).then((l) => {
      if (!cancelled) setLang(l)
    })
    return () => {
      cancelled = true
    }
  }, [activeFile])

  function patch(p: Partial<AutoAssessDraft>) {
    onChange({ ...draft, ...p })
  }

  function changeType(container: string | null) {
    if (container === null) {
      patch({ containerImage: null, gradingScript: '', assets: [], maxTimeSec: null, maxMemMb: null })
      return
    }
    const newType = autoEvalTypeOf(container)
    if (!newType) {
      patch({ containerImage: container })
      return
    }
    const oldType = autoEvalTypeOf(draft.containerImage)
    // Same editor kind and the scripts were already customised → keep them. Otherwise the new
    // template wins, same rule wui used. Note for the day a second editor:'TSL' entry exists:
    // a TSL draft's assets always contain the seeded tsl.json while the static template's never
    // do, so this comparison would classify every TSL exercise as customised — switching between
    // two TSL-family types would then keep the old container's script instead of re-templating.
    const keepScripts =
      oldType != null &&
      oldType.editor === newType.editor &&
      (draft.gradingScript !== oldType.evaluateScript ||
        JSON.stringify(assetsToMap(draft.assets)) !== JSON.stringify(oldType.assets))

    // A fresh TSL choice seeds a *valid empty* tsl.json (audit X-015): without it the first
    // compile is of the empty string, and the teacher's first sight of the deepest feature in
    // the app is a kotlinx parse error with Save disabled — on a screen that simultaneously,
    // correctly, says there are no tests yet. Seeded from the real solution file name so
    // requiredFiles and the field two inputs above cannot start out disagreeing.
    const freshAssets =
      newType.editor === 'TSL'
        ? [
            { file_name: TSL_SPEC_FILENAME, file_content: serializeSpec(emptySpec(draft.solutionFileName)) },
            ...mapToAssets(newType.assets),
          ]
        : mapToAssets(newType.assets)

    patch({
      containerImage: container,
      gradingScript: keepScripts ? draft.gradingScript : newType.evaluateScript,
      assets: keepScripts ? draft.assets : freshAssets,
      maxTimeSec:
        oldType != null && draft.maxTimeSec !== oldType.allowedTime
          ? draft.maxTimeSec
          : newType.allowedTime,
      maxMemMb:
        oldType != null && draft.maxMemMb !== oldType.allowedMemory
          ? draft.maxMemMb
          : newType.allowedMemory,
    })
    setActiveFile(EVAL_SCRIPT_TAB)
  }

  /**
   * The TSL spec's requiredFiles follows a rename for as long as the two still agree — the
   * seeding in changeType promises they cannot *start out* disagreeing, and without this a
   * rename one keystroke later would break that promise silently, failing every submission at
   * file validation. A hand-customised requiredFiles list is left alone.
   */
  function renameSolutionFile(next: string) {
    if (isTslContainer(draft.containerImage)) {
      const asset = draft.assets.find((a) => a.file_name === TSL_SPEC_FILENAME)
      const r = asset ? parseSpec(asset.file_content) : null
      if (
        r?.spec &&
        Array.isArray(r.spec.requiredFiles) &&
        r.spec.requiredFiles.length === 1 &&
        r.spec.requiredFiles[0] === draft.solutionFileName
      ) {
        const spec = { ...r.spec, requiredFiles: [next] }
        patch({
          solutionFileName: next,
          assets: draft.assets.map((a) =>
            a.file_name === TSL_SPEC_FILENAME ? { ...a, file_content: serializeSpec(spec) } : a,
          ),
        })
        return
      }
    }
    patch({ solutionFileName: next })
  }

  function setActiveContent(content: string) {
    if (activeFile === EVAL_SCRIPT_TAB) {
      patch({ gradingScript: content })
    } else {
      patch({
        assets: draft.assets.map((a) =>
          a.file_name === activeFile ? { ...a, file_content: content } : a,
        ),
      })
    }
  }

  function addAsset() {
    const name = window.prompt(t('library.assetFileName'))?.trim()
    if (!name) return
    if (name === EVAL_SCRIPT_TAB || draft.assets.some((a) => a.file_name === name)) return
    patch({ assets: [...draft.assets, { file_name: name, file_content: '' }] })
    setActiveFile(name)
  }

  function removeActiveAsset() {
    if (activeFile === EVAL_SCRIPT_TAB) return
    if (!window.confirm(t('library.removeAssetConfirm', { name: activeFile }))) return
    patch({ assets: draft.assets.filter((a) => a.file_name !== activeFile) })
    setActiveFile(EVAL_SCRIPT_TAB)
  }

  const typeOptions = useMemo(
    () => [
      { value: '', label: '–' },
      ...AUTO_EVAL_TYPES.map((tp) => ({ value: tp.container, label: tp.name })),
      ...(unknownContainer ? [{ value: unknownContainer, label: unknownContainer }] : []),
    ],
    [unknownContainer],
  )

  // Five labelled inputs is a lot of furniture for values that are set once and then read
  // rarely — and on the course exercise page, which cannot edit them at all, it was most of
  // what the tab showed. Collapsed to one line when not editing: every value still on screen,
  // just small, with the labels in tooltips rather than in boxes. No control to discover,
  // because the fields come back exactly when they become useful.
  const summary = [
    { label: t('library.solutionFileName'), value: draft.solutionFileName },
    ...(hasAuto
      ? [
          { label: t('library.autoAssessType'), value: type?.name ?? unknownContainer ?? '–' },
          { label: t('library.maxTimeSec'), value: draft.maxTimeSec === null ? '–' : `${draft.maxTimeSec} s` },
          { label: t('library.maxMemMb'), value: draft.maxMemMb === null ? '–' : `${draft.maxMemMb} MB` },
        ]
      : []),
  ]

  return (
    <Box display="flex" flexDirection="column" gap={2}>
      {!editing && (
        <Typography
          variant="body2"
          color="text.secondary"
          sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', columnGap: 1 }}
        >
          {summary.map((s, i) => (
            <Fragment key={s.label}>
              {i > 0 && (
                <Box component="span" aria-hidden sx={{ opacity: 0.4 }}>
                  ·
                </Box>
              )}
              <Tooltip title={s.label}>
                <Box component="span">{s.value}</Box>
              </Tooltip>
            </Fragment>
          ))}
        </Typography>
      )}

      {/*
      No `disabled={!editing}` on any of these any more: they only exist while editing, so the
      read-only variant they used to render is now the summary above. Keeping the prop would
      suggest a state this branch cannot be in.
      */}
      {editing && (
        <>
          <Box display="flex" gap={2} flexWrap="wrap">
            <TextField
              label={t('library.solutionFileName')}
              value={draft.solutionFileName}
              onChange={(e) => renameSolutionFile(e.target.value)}
              size="small"
              sx={{ minWidth: 200 }}
            />
            {/* No submission-type chooser. `TEXT_UPLOAD` is in the enum and in nothing else: the
                student's page renders a code editor whichever value is stored, both creation paths
                hardcode `TEXT_EDITOR`, and core reads the field only to *refuse* anything else from
                the embed and anonymous endpoints. So picking "file upload" changed a stored value,
                changed nothing a student saw, and quietly made the exercise un-embeddable.

                The draft still round-trips whatever is stored, so an exercise already saved as
                TEXT_UPLOAD keeps its value rather than being silently rewritten by opening this
                tab. */}
          </Box>

          <Box display="flex" gap={2} flexWrap="wrap" alignItems="flex-start">
            <FormControl size="small" sx={{ minWidth: 220 }}>
              <InputLabel id={evalTypeLabelId}>{t('library.autoAssessType')}</InputLabel>
              <Select
                labelId={evalTypeLabelId}
                label={t('library.autoAssessType')}
                value={draft.containerImage ?? ''}
                onChange={(e) => changeType(e.target.value === '' ? null : e.target.value)}
              >
                {typeOptions.map((o) => (
                  <MenuItem key={o.value} value={o.value}>
                    {o.label}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Box>

          {/* The execution limits, folded away: they are set once per container type and then
              almost never touched, and as two permanent boxes they read as things the author is
              expected to decide. Open on their own when either is empty — both are required and
              a blank one blocks Save (`error` below), which must not be hidden behind a
              disclosure nobody thought to open. */}
          {hasAuto && (
            <Accordion
              disableGutters
              elevation={0}
              defaultExpanded={draft.maxTimeSec === null || draft.maxMemMb === null}
              sx={{
                bgcolor: 'transparent',
                '&::before': { display: 'none' },
                '& .MuiAccordionSummary-root': { minHeight: 0, p: 0 },
                '& .MuiAccordionSummary-content': { my: 0.5 },
                '& .MuiAccordionDetails-root': { p: 0, pt: 1 },
              }}
            >
              <AccordionSummary expandIcon={<ExpandMoreOutlined fontSize="small" />}>
                <Typography variant="caption" color="text.secondary">
                  {t('library.executionLimits')}
                </Typography>
              </AccordionSummary>
              <AccordionDetails>
                <Box display="flex" gap={2} flexWrap="wrap">
                  <TextField
                    label={t('library.maxTimeSec')}
                    value={draft.maxTimeSec ?? ''}
                    onChange={(e) => patch({ maxTimeSec: parseIntOrNull(e.target.value) })}
                    size="small"
                    error={draft.maxTimeSec === null}
                    sx={{ width: 130 }}
                  />
                  <TextField
                    label={t('library.maxMemMb')}
                    value={draft.maxMemMb ?? ''}
                    onChange={(e) => patch({ maxMemMb: parseIntOrNull(e.target.value) })}
                    size="small"
                    error={draft.maxMemMb === null}
                    sx={{ width: 130 }}
                  />
                </Box>
              </AccordionDetails>
            </Accordion>
          )}

          {/* Guidance for choosing a container, so it goes with the chooser. */}
          {type?.helpTextKey && (
            <Typography variant="caption" color="text.secondary">
              {t(type.helpTextKey)}
            </Typography>
          )}
        </>
      )}

      {!hasAuto && (
        <Typography color="text.secondary" variant="body2">
          {t('library.noAutoAssess')}
        </Typography>
      )}

      {usesTslEditor && (
        <TslEditor
          value={draft.assets.find((a) => a.file_name === TSL_SPEC_FILENAME)?.file_content ?? ''}
          editing={editing}
          solutionFileName={draft.solutionFileName}
          onValidChange={onTslValidChange}
          onChange={(text) => {
            const others = draft.assets.filter((a) => a.file_name !== TSL_SPEC_FILENAME)
            patch({ assets: [{ file_name: TSL_SPEC_FILENAME, file_content: text }, ...others] })
          }}
        />
      )}

      {hasAuto && !usesTslEditor && (
        <Box>
          <Box display="flex" alignItems="center" gap={1}>
            <Tabs
              value={activeFile}
              onChange={(_, v) => setActiveFile(v)}
              variant="scrollable"
              scrollButtons="auto"
              sx={{ minHeight: 36, flex: 1, '& .MuiTab-root': { minHeight: 36, textTransform: 'none' } }}
            >
              <Tab value={EVAL_SCRIPT_TAB} label={t('library.evalScript')} />
              {draft.assets.map((a) => (
                <Tab key={a.file_name} value={a.file_name} label={a.file_name} />
              ))}
            </Tabs>
            {filesEditable && (
              <>
                <Tooltip title={t('library.addAssetFile')}>
                  <IconButton size="small" onClick={addAsset}>
                    <AddOutlined fontSize="small" />
                  </IconButton>
                </Tooltip>
                <Tooltip title={t('library.removeAssetFile')}>
                  <span>
                    <IconButton
                      size="small"
                      onClick={removeActiveAsset}
                      disabled={activeFile === EVAL_SCRIPT_TAB}
                    >
                      <DeleteOutlineOutlined fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              </>
            )}
          </Box>
          <Box mt={1}>
            <CodeEditor
              ariaLabel={activeFile}
              key={activeFile}
              value={activeContent}
              onChange={setActiveContent}
              language={lang}
              readOnly={!filesEditable}
              minHeight="22rem"
            />
          </Box>
        </Box>
      )}
    </Box>
  )
}

function parseIntOrNull(v: string): number | null {
  if (v.trim() === '') return null
  const n = Number(v)
  return Number.isInteger(n) && n > 0 ? n : null
}

