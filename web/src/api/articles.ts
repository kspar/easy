import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from './client.ts'
import { useAuth } from '../auth/useAuth.ts'

/**
 * What an alias may look like, mirroring core's `@Pattern` so the error arrives before the request.
 *
 * At least one letter is not a style rule: core resolves an alias before falling back to a numeric
 * id, so an all-digit alias would shadow the article whose id it matches.
 */
export const ALIAS_PATTERN = /^[\w-]*[a-zA-Z][\w-]*$/

export interface ArticleUser {
  /** A username. Admin-only, so absent for everyone else — including anonymous readers. */
  id?: string
  given_name: string
  family_name: string
}

export interface ArticleAlias {
  id: string
  created_at: string
  created_by: string
}

export interface Article {
  id: string
  title: string
  created_at: string
  last_modified: string
  owner: ArticleUser
  author: ArticleUser
  text_html: string | null
  /** The Markdown source. Admin-only — a reader gets `text_html`. */
  text_md?: string | null
  /** Admin-only. False is a draft: nobody but an admin can read it. */
  published?: boolean
  /** Admin-only. */
  aliases?: ArticleAlias[]
}

/** A row of the admin index. Flatter than {@link Article}: no body, and aliases are plain strings. */
export interface ArticleListItem {
  id: string
  title: string
  aliases: string[]
  created_at: string
  last_modified: string
  published: boolean
}

export interface ArticleDraft {
  title: string
  text_md: string | null
  published: boolean
}

/**
 * One article, by id or by alias.
 *
 * **Two endpoints, chosen by whether anyone is signed in.** A published article has to render for a
 * visitor with no account — that is the point of a short hand-writeable alias — and core serves that
 * from `/unauth/articles/{id}`, which cannot return a draft. Signed in, we ask the authenticated
 * endpoint instead, because an admin needs to see their own drafts and the extra fields that come
 * with them.
 */
export function useArticle(idOrAlias: string | undefined) {
  const { authenticated } = useAuth()
  return useQuery({
    queryKey: ['articles', idOrAlias, authenticated],
    queryFn: () =>
      authenticated
        ? apiFetch<Article>(`/articles/${idOrAlias}`)
        : apiFetch<Article>(`/unauth/articles/${idOrAlias}`, { noAuth: true }),
    enabled: !!idOrAlias,
  })
}

/** Every article including drafts. Admin-only in core, so do not call it for anyone else. */
export function useArticles(enabled = true) {
  return useQuery({
    queryKey: ['articles'],
    queryFn: () =>
      apiFetch<{ articles?: ArticleListItem[] }>('/articles').then((r) => r.articles ?? []),
    enabled,
  })
}

/**
 * Everything article-shaped is invalidated after every write.
 *
 * The prefix covers both the index and every individual article, which matters because an alias
 * change moves an article's URL: the entry cached under the old alias is now wrong, and nothing
 * else would evict it.
 */
function useInvalidateArticles() {
  const qc = useQueryClient()
  return () => {
    void qc.invalidateQueries({ queryKey: ['articles'] })
  }
}

export function useCreateArticle() {
  const invalidate = useInvalidateArticles()
  return useMutation({
    mutationFn: (draft: ArticleDraft) =>
      apiFetch<{ id: string }>('/articles', { method: 'POST', body: draft }),
    onSuccess: invalidate,
  })
}

export function useUpdateArticle() {
  const invalidate = useInvalidateArticles()
  return useMutation({
    mutationFn: ({ id, ...draft }: ArticleDraft & { id: string }) =>
      apiFetch(`/articles/${id}`, { method: 'PUT', body: draft }),
    onSuccess: invalidate,
  })
}

export function useDeleteArticle() {
  const invalidate = useInvalidateArticles()
  return useMutation({
    mutationFn: (id: string) => apiFetch(`/articles/${id}`, { method: 'DELETE' }),
    onSuccess: invalidate,
  })
}

export function useCreateAlias() {
  const invalidate = useInvalidateArticles()
  return useMutation({
    mutationFn: ({ id, alias }: { id: string; alias: string }) =>
      apiFetch(`/articles/${id}/aliases`, { method: 'POST', body: { alias } }),
    onSuccess: invalidate,
  })
}

export function useDeleteAlias() {
  const invalidate = useInvalidateArticles()
  return useMutation({
    mutationFn: ({ id, alias }: { id: string; alias: string }) =>
      apiFetch(`/articles/${id}/aliases/${alias}`, { method: 'DELETE' }),
    onSuccess: invalidate,
  })
}

/**
 * Where an article lives. The alias if it has one, the id otherwise — core resolves either in the
 * same path segment, and an article with no alias still has to be reachable.
 */
export function articleLink(article: { id: string; aliases?: string[] | ArticleAlias[] }): string {
  const first = article.aliases?.[0]
  const alias = typeof first === 'string' ? first : first?.id
  return `/a/${alias ?? article.id}`
}
