import type { DirAccessLevel } from '../../api/types.ts'

/**
 * Re-exported, not defined here.
 *
 * This file had its own copy, differing from `components/spaLink.ts` only by a `stopPropagation`
 * flag — which is exactly how a codebase ends up with four of them and a sidebar that has none. The
 * flag moved to the canonical one; this export stays so the dozen `from './links.ts'` imports in
 * `features/library` and `features/articles` keep working.
 */
export { spaLinkProps } from '../../components/spaLink.ts'

export function slugify(s: string): string {
  return s.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9À-ɏ-]/g, '')
}

export function dirLink(id: string, name: string): string {
  return `/library/dir/${id}/${slugify(name)}`
}

export function exerciseLink(id: string, title: string): string {
  return `/library/exercise/${id}/${slugify(title)}`
}

const ACCESS_ORDER: DirAccessLevel[] = ['P', 'PR', 'PRA', 'PRAW', 'PRAWM']

export function hasAccess(level: DirAccessLevel, required: DirAccessLevel): boolean {
  return ACCESS_ORDER.indexOf(level) >= ACCESS_ORDER.indexOf(required)
}
