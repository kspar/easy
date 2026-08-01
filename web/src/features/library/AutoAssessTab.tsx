import { useEffect, useId, useMemo, useState } from 'react'
import {
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
import { AddOutlined, DeleteOutlineOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { Extension } from '@codemirror/state'
import CodeEditor from '../../components/CodeEditor.tsx'
import { languageFromFilename } from '../course-exercise/editorLanguage.ts'
import type { SolutionFileType } from '../../api/types.ts'
import { AUTO_EVAL_TYPES, autoEvalTypeOf, isTslContainer } from './autoEvalTypes.ts'
import { assetsToMap, mapToAssets, TSL_SPEC_FILENAME, type AutoAssessDraft } from './exerciseDraft.ts'
import TslEditor from './tsl/TslEditor.tsx'

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
  const fileTypeLabelId = useId()
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
    // template wins, same rule wui used.
    const keepScripts =
      oldType != null &&
      oldType.editor === newType.editor &&
      (draft.gradingScript !== oldType.evaluateScript ||
        JSON.stringify(assetsToMap(draft.assets)) !== JSON.stringify(oldType.assets))

    patch({
      containerImage: container,
      gradingScript: keepScripts ? draft.gradingScript : newType.evaluateScript,
      assets: keepScripts ? draft.assets : mapToAssets(newType.assets),
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

  return (
    <Box display="flex" flexDirection="column" gap={2}>
      <Box display="flex" gap={2} flexWrap="wrap">
        <TextField
          label={t('library.solutionFileName')}
          value={draft.solutionFileName}
          onChange={(e) => patch({ solutionFileName: e.target.value })}
          disabled={!editing}
          size="small"
          sx={{ minWidth: 200 }}
        />
        <FormControl size="small" sx={{ minWidth: 180 }} disabled={!editing}>
          <InputLabel id={fileTypeLabelId}>{t('library.solutionFileType')}</InputLabel>
          <Select
            labelId={fileTypeLabelId}
            label={t('library.solutionFileType')}
            value={draft.solutionFileType}
            onChange={(e) => patch({ solutionFileType: e.target.value as SolutionFileType })}
          >
            <MenuItem value="TEXT_EDITOR">{t('library.solutionTypeEditor')}</MenuItem>
            <MenuItem value="TEXT_UPLOAD">{t('library.solutionTypeUpload')}</MenuItem>
          </Select>
        </FormControl>
      </Box>

      <Box display="flex" gap={2} flexWrap="wrap" alignItems="flex-start">
        <FormControl size="small" sx={{ minWidth: 220 }} disabled={!editing}>
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
        {hasAuto && (
          <>
            <TextField
              label={t('library.maxTimeSec')}
              value={draft.maxTimeSec ?? ''}
              onChange={(e) => patch({ maxTimeSec: parseIntOrNull(e.target.value) })}
              disabled={!editing}
              size="small"
              error={editing && draft.maxTimeSec === null}
              sx={{ width: 130 }}
            />
            <TextField
              label={t('library.maxMemMb')}
              value={draft.maxMemMb ?? ''}
              onChange={(e) => patch({ maxMemMb: parseIntOrNull(e.target.value) })}
              disabled={!editing}
              size="small"
              error={editing && draft.maxMemMb === null}
              sx={{ width: 130 }}
            />
          </>
        )}
      </Box>

      {type?.helpText && (
        <Typography variant="caption" color="text.secondary">
          {type.helpText}
        </Typography>
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

