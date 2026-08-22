/**
 * Which buttons a Markdown toolbar shows.
 *
 * Separate from `MarkdownToolbar.tsx` because a file that exports both a component and constants
 * breaks Fast Refresh — the lint rule that says so is an error here, not a warning.
 */
export type MarkdownTool =
  | 'bold'
  | 'italic'
  | 'strikethrough'
  | 'code'
  | 'heading'
  | 'bulletList'
  | 'numberedList'
  | 'quote'
  | 'link'
  | 'image'
  | 'codeBlock'
  | 'math'
  | 'table'
  | 'rule'
  | 'divider'

/**
 * The inline feedback editor: what fits in a box wedged between two lines of code, and what a
 * comment on someone's homework actually needs.
 */
export const COMPACT_TOOLS: MarkdownTool[] = [
  'bold',
  'italic',
  'code',
  'divider',
  'bulletList',
  'numberedList',
]

/** The exercise editor, which owns a whole tab: everything the renderer supports. */
export const FULL_TOOLS: MarkdownTool[] = [
  'heading',
  'bold',
  'italic',
  'strikethrough',
  'code',
  'divider',
  'bulletList',
  'numberedList',
  'quote',
  'divider',
  'link',
  'image',
  'codeBlock',
  'math',
  'table',
  'rule',
]
