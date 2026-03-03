import { useState } from 'react'
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  TextField,
} from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { useCreateExercise } from '../../api/library.ts'

function slugify(s: string): string {
  return s.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9\u00C0-\u024F-]/g, '')
}

export default function CreateExerciseDialog({
  parentDirId,
  open,
  onClose,
}: {
  parentDirId: string | null
  open: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const createExercise = useCreateExercise()

  function handleCreate() {
    if (!title.trim()) return
    createExercise.mutate(
      {
        title: title.trim(),
        parent_dir_id: parentDirId,
        public: true,
        grader_type: 'TEACHER',
        solution_file_name: 'lahendus.py',
        solution_file_type: 'TEXT_EDITOR',
        anonymous_autoassess_enabled: false,
      },
      {
        onSuccess: (data) => {
          setTitle('')
          onClose()
          navigate(`/library/exercise/${data.id}/${slugify(title.trim())}`)
        },
      },
    )
  }

  function handleClose() {
    setTitle('')
    onClose()
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="xs" fullWidth
      TransitionProps={{ onEntered: (node) => { (node as HTMLElement).querySelector('input')?.focus() } }}
    >
      <DialogTitle>{t('library.createExercise')}</DialogTitle>
      <DialogContent sx={{ pt: '8px !important' }}>
        <TextField
          fullWidth
          label={t('library.exerciseTitle')}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          autoFocus
          inputProps={{ maxLength: 100 }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && title.trim() && !createExercise.isPending) handleCreate()
          }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose}>{t('general.cancel')}</Button>
        <Button
          onClick={handleCreate}
          variant="contained"
          disabled={createExercise.isPending || !title.trim()}
        >
          {createExercise.isPending ? t('general.saving') : t('general.add')}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
