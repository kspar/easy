import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import {
  AddOutlined,
  DeleteOutlineOutlined,
  EditOutlined,
  LinkOutlined,
} from '@mui/icons-material'
import MarkdownEditor from '../../components/markdown/MarkdownEditor.tsx'
import RenderedMarkdown from '../../components/markdown/RenderedMarkdown.tsx'
import { useMarkdownPreview } from '../../api/exercises.ts'
import { useAuth } from '../../auth/useAuth.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import {
  ALIAS_PATTERN,
  useArticle,
  useCreateAlias,
  useDeleteAlias,
  useDeleteArticle,
  useUpdateArticle,
} from '../../api/articles.ts'
import ErrorAlert from '../../components/ErrorAlert.tsx'

/**
 * One article, at `/a/<alias>`.
 *
 * Reading and editing share a URL, the way the library exercise page does — it is this app's
 * authoring idiom, and it means the thing an admin checks after saving is the thing a reader sees.
 *
 * The page renders for visitors with no account: a published article is public, and the alias is
 * short enough to write on a slide. Everything admin-only below is gated on the *response*, not on
 * the role — core omits `published` and `aliases` for anyone else, so there is nothing to hide by
 * hand.
 */
export default function ArticlePage() {
  const { alias } = useParams<{ alias: string }>()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { activeRole } = useAuth()
  const isAdmin = activeRole === 'admin'

  const { data: article, isLoading, error } = useArticle(alias)
  const update = useUpdateArticle()
  const remove = useDeleteArticle()
  const addAlias = useCreateAlias()
  const removeAlias = useDeleteAlias()

  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState('')
  const [textMd, setTextMd] = useState('')
  const [published, setPublished] = useState(false)
  const [newAlias, setNewAlias] = useState('')
  const [aliasError, setAliasError] = useState('')

  usePageTitle(article?.title ?? t('articles.title'))

  // Seeded here rather than in an effect on `editing`: the draft is filled once, at the moment
  // editing begins, so a refetch arriving mid-edit cannot discard what is being typed.
  function startEditing() {
    if (!article) return
    setTitle(article.title)
    setTextMd(article.text_md ?? '')
    setPublished(article.published ?? false)
    setEditing(true)
  }

  const previewHtml = useMarkdownPreview(editing ? textMd : '')
  // Fall back to the saved HTML for the one debounce tick before the first preview arrives, so the
  // pane does not blink empty on entering edit mode.
  const shownHtml = editing
    ? previewHtml || (textMd.trim() ? (article?.text_html ?? '') : '')
    : (article?.text_html ?? '')

  if (isLoading) {
    return (
      <Box display="flex" justifyContent="center" py={6}>
        <CircularProgress />
      </Box>
    )
  }

  // A draft, a deleted article and a typo in the alias are the same answer from core, deliberately
  // — so this says "not found" rather than guessing which one it was.
  if (error || !article) {
    return (
      <Box sx={{ py: 6, textAlign: 'center' }}>
        <Typography variant="h5" gutterBottom>
          {t('articles.notFound')}
        </Typography>
        <Typography color="text.secondary">{t('articles.notFoundMsg')}</Typography>
      </Box>
    )
  }

  const canSave = title.trim().length > 0 && !update.isPending

  async function save() {
    if (!article) return
    await update.mutateAsync({
      id: article.id,
      title: title.trim(),
      text_md: textMd.trim() === '' ? null : textMd,
      published,
    })
    setEditing(false)
  }

  async function handleAddAlias() {
    if (!article) return
    const value = newAlias.trim()
    if (!value) return
    if (!ALIAS_PATTERN.test(value)) {
      setAliasError(t('articles.aliasInvalid'))
      return
    }
    try {
      await addAlias.mutateAsync({ id: article.id, alias: value })
      setNewAlias('')
      setAliasError('')
    } catch {
      setAliasError(t('articles.aliasInUse'))
    }
  }

  async function handleDelete() {
    if (!article) return
    if (!window.confirm(t('articles.confirmDelete', { title: article.title }))) return
    await remove.mutateAsync(article.id)
    navigate('/articles')
  }

  return (
    <Box>
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 2 }}>
        <Typography variant="h5" sx={{ flexGrow: 1 }}>
          {editing ? t('articles.editing') : article.title}
        </Typography>

        {article.published === false && <Chip size="small" label={t('articles.draft')} />}

        {isAdmin && !editing && (
          <>
            <Button startIcon={<EditOutlined />} onClick={startEditing} sx={{ textTransform: 'none' }}>
              {t('general.edit')}
            </Button>
            {/* Only offered on a draft, because core refuses to delete a published article. */}
            {article.published === false && (
              <Button
                color="error"
                startIcon={<DeleteOutlineOutlined />}
                onClick={handleDelete}
                sx={{ textTransform: 'none' }}
              >
                {t('general.delete')}
              </Button>
            )}
          </>
        )}

        {editing && (
          <>
            <Button onClick={() => setEditing(false)} sx={{ textTransform: 'none' }}>
              {t('general.cancel')}
            </Button>
            <Button variant="contained" onClick={save} disabled={!canSave} sx={{ textTransform: 'none' }}>
              {update.isPending ? t('general.saving') : t('general.save')}
            </Button>
          </>
        )}
      </Stack>

      {update.isError && <ErrorAlert sx={{ mb: 2 }} />}

      <Box
        display="grid"
        gridTemplateColumns={editing ? { xs: '1fr', lg: 'minmax(0, 1fr) minmax(0, 1fr)' } : '1fr'}
        gap={3}
        alignItems="start"
      >
        <Paper variant="outlined" sx={{ p: 3, minWidth: 0 }}>
          {editing && (
            <>
              <Typography variant="h5" gutterBottom>
                {title}
              </Typography>
              <Divider sx={{ mb: 2 }} />
            </>
          )}
          <RenderedMarkdown html={shownHtml} />
        </Paper>

        {editing && (
          <Box minWidth={0}>
            <TextField
              fullWidth
              size="small"
              label={t('articles.articleTitle')}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              sx={{ mb: 2 }}
            />
            <MarkdownEditor
              ariaLabel={t('articles.text')}
              value={textMd}
              onChange={setTextMd}
              placeholder={t('articles.textPlaceholder')}
              minHeight="30rem"
            />

            <Stack direction="row" alignItems="center" spacing={1} sx={{ mt: 2 }}>
              <Chip
                label={published ? t('articles.published') : t('articles.draft')}
                color={published ? 'primary' : 'default'}
                variant={published ? 'filled' : 'outlined'}
                onClick={() => setPublished((v) => !v)}
              />
              <Typography variant="caption" color="text.secondary">
                {published ? t('articles.publishedHelp') : t('articles.draftHelp')}
              </Typography>
            </Stack>

            {/* Aliases are the URL, so they are edited where the article is. */}
            <Typography variant="subtitle2" sx={{ mt: 3, mb: 1 }}>
              {t('articles.aliases')}
            </Typography>
            <Stack spacing={0.5} sx={{ mb: 1 }}>
              {(article.aliases ?? []).map((a) => (
                <Stack key={a.id} direction="row" alignItems="center" spacing={1}>
                  <LinkOutlined fontSize="small" color="action" />
                  <Typography variant="body2" sx={{ fontFamily: 'monospace', flexGrow: 1 }}>
                    /a/{a.id}
                  </Typography>
                  <Tooltip title={t('general.remove')}>
                    <IconButton size="small" onClick={() => removeAlias.mutate({ id: article.id, alias: a.id })}>
                      <DeleteOutlineOutlined fontSize="small" />
                    </IconButton>
                  </Tooltip>
                </Stack>
              ))}
              {(article.aliases ?? []).length === 0 && (
                <Typography variant="caption" color="text.secondary">
                  {t('articles.noAliases')}
                </Typography>
              )}
            </Stack>
            <Stack direction="row" spacing={1}>
              <TextField
                size="small"
                placeholder={t('articles.aliasPlaceholder')}
                value={newAlias}
                onChange={(e) => { setNewAlias(e.target.value); setAliasError('') }}
                onKeyDown={(e) => { if (e.key === 'Enter') handleAddAlias() }}
                error={!!aliasError}
                helperText={aliasError || t('articles.aliasHelp')}
              />
              <Button
                startIcon={<AddOutlined />}
                onClick={handleAddAlias}
                disabled={!newAlias.trim() || addAlias.isPending}
                sx={{ textTransform: 'none', alignSelf: 'flex-start', height: 40 }}
              >
                {t('general.add')}
              </Button>
            </Stack>
          </Box>
        )}
      </Box>
    </Box>
  )
}
