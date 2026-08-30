import { Alert, Box, TextField } from '@mui/material'
import { useTranslation } from 'react-i18next'
import MarkdownEditor from '../../components/markdown/MarkdownEditor.tsx'
import { TITLE_MAX_LENGTH } from './exerciseDraft.ts'

export default function ExerciseTextTab({
  title,
  textMd,
  editing,
  legacyNoMarkdown = false,
  onTitleChange,
  onTextChange,
}: {
  title: string
  textMd: string
  editing: boolean
  /**
   * The exercise has rendered text but no Markdown source — the shape everything authored before
   * the AsciiDoc migration is still in. See the alert below.
   */
  legacyNoMarkdown?: boolean
  onTitleChange: (title: string) => void
  onTextChange: (textMd: string) => void
}) {
  const { t } = useTranslation()

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
          // Whole sentences with the field name interpolated, rather than a fragment glued to a
          // label at the call site: Estonian needs a case ending on that noun, which string
          // concatenation cannot give it.
          editing && titleTooLong
            ? t('validation.tooLong', {
                field: t('library.exerciseTitle'),
                max: TITLE_MAX_LENGTH,
              })
            : editing && titleEmpty
              ? t('validation.required', { field: t('library.exerciseTitle') })
              : ' '
        }
      />

      {/*
        Saving derives text_html from text_md, so an empty box here does not mean "leave the text
        alone" — it means "delete the text". For an exercise that never got a Markdown source that
        is a silent data loss triggered by editing the title, which is why the page refuses to save
        until something is typed.
      */}
      {legacyNoMarkdown && editing && (
        <Alert severity="warning">{t('library.noMarkdownSource')}</Alert>
      )}

      <MarkdownEditor
        ariaLabel={t('library.tabExercise')}
        value={textMd}
        onChange={onTextChange}
        readOnly={!editing}
        placeholder={t('library.exerciseTextPlaceholder')}
        minHeight="30rem"
      />
    </Box>
  )
}
