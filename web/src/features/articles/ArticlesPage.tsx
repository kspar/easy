import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { AddOutlined } from '@mui/icons-material'
import RelativeTime from '../../components/RelativeTime.tsx'
import usePageTitle from '../../hooks/usePageTitle.ts'
import { spaLinkProps } from '../library/links.ts'
import { articleLink, useArticles, useCreateArticle } from '../../api/articles.ts'
import ErrorAlert from '../../components/ErrorAlert.tsx'

/**
 * The admin index of articles, at `/articles`.
 *
 * Admin-only by design rather than by omission: a reader reaches an article from a link in a course
 * page, an e-mail or the IdP, so nobody else needs to enumerate them. Core's list endpoint is
 * @Secured to admins for the same reason, so this page has nothing to filter.
 *
 * Creating is a title and nothing else — a new article starts as an empty draft and is written on
 * its own page, where the preview is.
 */
export default function ArticlesPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  usePageTitle(t('articles.title'))

  const { data: articles, isLoading } = useArticles()
  const create = useCreateArticle()

  const [creating, setCreating] = useState(false)
  const [newTitle, setNewTitle] = useState('')

  async function handleCreate() {
    const title = newTitle.trim()
    if (!title) return
    const { id } = await create.mutateAsync({ title, text_md: null, published: false })
    setNewTitle('')
    setCreating(false)
    navigate(`/a/${id}`)
  }

  return (
    <Box>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="h5">{t('articles.title')}</Typography>
        <Button
          variant="contained"
          startIcon={<AddOutlined />}
          onClick={() => setCreating((v) => !v)}
          sx={{ textTransform: 'none' }}
        >
          {t('articles.newArticle')}
        </Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('articles.intro')}
      </Typography>

      {creating && (
        <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
          <Stack direction="row" spacing={1}>
            <TextField
              fullWidth
              size="small"
              autoFocus
              label={t('articles.articleTitle')}
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && !create.isPending) handleCreate() }}
            />
            <Button
              variant="contained"
              onClick={handleCreate}
              disabled={!newTitle.trim() || create.isPending}
              sx={{ textTransform: 'none', height: 40 }}
            >
              {create.isPending ? t('general.adding') : t('general.add')}
            </Button>
          </Stack>
        </Paper>
      )}

      {create.isError && <ErrorAlert sx={{ mb: 2 }} />}

      {isLoading && (
        <Box display="flex" justifyContent="center" py={4}>
          <CircularProgress />
        </Box>
      )}

      {articles && articles.length === 0 && (
        <Typography color="text.secondary">{t('articles.none')}</Typography>
      )}

      <Stack spacing={1.5}>
        {(articles ?? []).map((a) => (
          <Paper key={a.id} variant="outlined" sx={{ p: 2 }}>
            <Stack direction="row" alignItems="center" spacing={1}>
              <Typography
                component="a"
                variant="subtitle1"
                {...spaLinkProps(articleLink(a), navigate)}
                sx={{ textDecoration: 'none', color: 'inherit', '&:hover': { textDecoration: 'underline' } }}
              >
                {a.title}
              </Typography>
              {!a.published && <Chip size="small" label={t('articles.draft')} />}
              <Box sx={{ flexGrow: 1 }} />
              <Typography variant="caption" color="text.secondary">
                {t('articles.modified')} <RelativeTime date={a.last_modified} />
              </Typography>
            </Stack>
            <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
              {a.aliases.length > 0 ? a.aliases.map((x) => `/a/${x}`).join('  ') : t('articles.noAliases')}
            </Typography>
          </Paper>
        ))}
      </Stack>
    </Box>
  )
}
