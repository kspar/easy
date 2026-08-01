import { useEffect, useState } from 'react'
import { Box, TextField } from '@mui/material'
import { useTranslation } from 'react-i18next'
import type { Extension } from '@codemirror/state'
import CodeEditor from '../../components/CodeEditor.tsx'
import { TITLE_MAX_LENGTH } from './exerciseDraft.ts'

/** Loaded once and reused — creating it per render would rebuild the editor on every keystroke. */
let markdownExtension: Extension | undefined

export default function ExerciseTextTab({
  title,
  textMd,
  editing,
  onTitleChange,
  onTextChange,
}: {
  title: string
  textMd: string
  editing: boolean
  onTitleChange: (title: string) => void
  onTextChange: (textMd: string) => void
}) {
  const { t } = useTranslation()
  const [lang, setLang] = useState<Extension | undefined>(markdownExtension)

  useEffect(() => {
    if (markdownExtension) return
    let cancelled = false
    import('@codemirror/lang-markdown').then((m) => {
      markdownExtension = m.markdown()
      if (!cancelled) setLang(markdownExtension)
    })
    return () => {
      cancelled = true
    }
  }, [])

  const titleTooLong = title.length > TITLE_MAX_LENGTH
  const titleEmpty = title.trim().length === 0

  return (
    <Box display="flex" flexDirection="column" gap={2}>
      <TextField
        label={t('library.exerciseTitle')}
        value={title}
        onChange={(e) => onTitleChange(e.target.value)}
        disabled={!editing}
        size="small"
        fullWidth
        error={editing && (titleTooLong || titleEmpty)}
        helperText={
          editing && titleTooLong
            ? `${t('validation.tooLong')} ${TITLE_MAX_LENGTH}`
            : editing && titleEmpty
              ? `${t('library.exerciseTitle')} ${t('validation.required')}`
              : ' '
        }
      />
      <CodeEditor
        value={textMd}
        onChange={onTextChange}
        language={lang}
        readOnly={!editing}
        placeholder={t('library.exerciseTextPlaceholder')}
        minHeight="30rem"
      />
    </Box>
  )
}

