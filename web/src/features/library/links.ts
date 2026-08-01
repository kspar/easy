import type { MouseEvent } from 'react'
import type { DirAccessLevel } from '../../api/types.ts'

export function slugify(s: string): string {
  return s.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9À-ɏ-]/g, '')
}

export function dirLink(id: string, name: string): string {
  return `/library/dir/${id}/${slugify(name)}`
}

export function exerciseLink(id: string, title: string): string {
  return `/library/exercise/${id}/${slugify(title)}`
}

/**
 * Props for an anchor that navigates in-SPA on a plain click but leaves ctrl/cmd/shift-click
 * (and middle-click) to the browser, so library links can still be opened in a new tab.
 */
export function spaLinkProps(href: string, navigate: (to: string) => void, stopPropagation = false) {
  return {
    href,
    onClick: (e: MouseEvent<HTMLAnchorElement>) => {
      if (stopPropagation) e.stopPropagation()
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
      e.preventDefault()
      navigate(href)
    },
  }
}

const ACCESS_ORDER: DirAccessLevel[] = ['P', 'PR', 'PRA', 'PRAW', 'PRAWM']

export function hasAccess(level: DirAccessLevel, required: DirAccessLevel): boolean {
  return ACCESS_ORDER.indexOf(level) >= ACCESS_ORDER.indexOf(required)
}
