import { useState, type MouseEvent, type ReactNode } from 'react'
import { Box, IconButton, Menu, MenuItem, Tooltip } from '@mui/material'
import {
  CodeOutlined,
  DataObjectOutlined,
  FormatBoldOutlined,
  FormatItalicOutlined,
  FormatListBulletedOutlined,
  FormatListNumberedOutlined,
  FormatQuoteOutlined,
  FunctionsOutlined,
  HorizontalRuleOutlined,
  ImageOutlined,
  LinkOutlined,
  StrikethroughSOutlined,
  TableChartOutlined,
  TitleOutlined,
  WrapTextOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import type { EditorView } from '@codemirror/view'
import type { MarkdownTool } from './markdownTools.ts'
import { useSoftWrap } from '../editorWrap.ts'
import {
  applyFormat,
  insertCodeBlock,
  insertImage,
  insertLink,
  insertMathBlock,
  insertRule,
  insertTable,
  setHeading,
  toggleLinePrefix,
  toggleOrderedList,
} from './markdownActions.ts'

/**
 * Formatting bar for the Markdown editors.
 *
 * Which buttons appear is the caller's choice, because the two places that need one have very
 * different room: the inline feedback editor is a box wedged between two lines of code, and the
 * exercise editor owns a whole tab. Both drive the same commands. The presets live in
 * `markdownTools.ts`.
 */
export default function MarkdownToolbar({
  view,
  tools,
  disabled = false,
  onPickFile,
}: {
  /** Null until CodeMirror has mounted; every button no-ops until then. */
  view: EditorView | null
  tools: MarkdownTool[]
  disabled?: boolean
  /**
   * Opens a file picker. Omitted where uploading is not offered, and the image button then goes
   * straight to its by-URL behaviour rather than opening a menu with one item in it.
   */
  onPickFile?: () => void
}) {
  const { t } = useTranslation()
  // Lives here rather than in one editor, so every markdown surface that has a toolbar has the
  // switch: the exercise text, an article, and the comment box wedged between two lines of code.
  const { wrap, toggleWrap } = useSoftWrap('markdown')
  const [headingAnchor, setHeadingAnchor] = useState<HTMLElement | null>(null)
  const [imageAnchor, setImageAnchor] = useState<HTMLElement | null>(null)
  const [mathAnchor, setMathAnchor] = useState<HTMLElement | null>(null)

  const btnSx = {
    p: '5px',
    borderRadius: '6px',
    color: 'text.secondary',
    '&:hover': { color: 'text.primary', bgcolor: 'action.hover' },
  }
  const iconSx = { fontSize: 17 }

  function run(fn: (v: EditorView) => void) {
    return () => {
      if (view && !disabled) fn(view)
    }
  }

  function button(
    key: string,
    label: string,
    icon: ReactNode,
    onClick: (e: MouseEvent<HTMLElement>) => void,
  ) {
    return (
      <Tooltip key={key} title={label}>
        {/* span: a disabled IconButton fires no events, so Tooltip needs a live child to bind to */}
        <span>
          <IconButton size="small" sx={btnSx} onClick={onClick} disabled={disabled} aria-label={label}>
            {icon}
          </IconButton>
        </span>
      </Tooltip>
    )
  }

  return (
    <Box
      // Fills its row so the wrap toggle at the far end has somewhere to be pushed to. Both
      // spellings are needed: `flex` for the editor's flex header, `width` for the comment box's
      // plain block one.
      sx={{ display: 'flex', alignItems: 'center', gap: '2px', flexWrap: 'wrap', flex: 1, minWidth: 0, width: '100%' }}
      role="toolbar"
      aria-label={t('markdown.toolbar')}
    >
      {tools.map((tool, i) => {
        switch (tool) {
          case 'divider':
            return <Box key={`d${i}`} sx={{ width: 8 }} />
          case 'heading':
            return (
              <span key="heading">
                {button('heading', t('markdown.heading'), <TitleOutlined sx={iconSx} />, (e) =>
                  setHeadingAnchor(e.currentTarget),
                )}
                <Menu
                  anchorEl={headingAnchor}
                  open={Boolean(headingAnchor)}
                  onClose={() => setHeadingAnchor(null)}
                  // Closing a Menu returns focus to whatever opened it, which happens after
                  // setHeading has already focused the editor — so the caret ended up on the
                  // toolbar button and looked like it had disappeared. Suppressing the restore
                  // alone just moved it to <body>: the focus trap releases focus as it unmounts,
                  // still after our call. Re-focusing once the transition has finished is the
                  // only point at which nothing else is about to move it.
                  disableRestoreFocus
                  TransitionProps={{ onExited: () => view?.focus() }}
                >
                  {([1, 2, 3] as const).map((level) => (
                    <MenuItem
                      key={level}
                      onClick={() => {
                        setHeadingAnchor(null)
                        if (view) setHeading(view, level)
                      }}
                    >
                      {t(`markdown.h${level}`)}
                    </MenuItem>
                  ))}
                </Menu>
              </span>
            )
          case 'bold':
            return button('bold', t('markdown.bold'), <FormatBoldOutlined sx={iconSx} />,
              run((v) => applyFormat(v, '**', '**', t('markdown.boldText'))))
          case 'italic':
            return button('italic', t('markdown.italic'), <FormatItalicOutlined sx={iconSx} />,
              run((v) => applyFormat(v, '_', '_', t('markdown.italicText'))))
          case 'strikethrough':
            return button('strikethrough', t('markdown.strikethrough'), <StrikethroughSOutlined sx={iconSx} />,
              run((v) => applyFormat(v, '~~', '~~', t('markdown.strikethroughText'))))
          case 'code':
            return button('code', t('markdown.code'), <CodeOutlined sx={iconSx} />,
              run((v) => applyFormat(v, '`', '`', t('markdown.codeText'))))
          case 'bulletList':
            return button('bulletList', t('markdown.bulletList'), <FormatListBulletedOutlined sx={iconSx} />,
              run((v) => toggleLinePrefix(v, '- ')))
          case 'numberedList':
            return button('numberedList', t('markdown.numberedList'), <FormatListNumberedOutlined sx={iconSx} />,
              run(toggleOrderedList))
          case 'quote':
            return button('quote', t('markdown.quote'), <FormatQuoteOutlined sx={iconSx} />,
              run((v) => toggleLinePrefix(v, '> ')))
          case 'link':
            return button('link', t('markdown.link'), <LinkOutlined sx={iconSx} />,
              run((v) => insertLink(v, t('markdown.linkUrl'), t('markdown.linkText'))))
          case 'image':
            // Two ways to get an image in, and the older one is not obsolete: an image already
            // hosted somewhere else is ordinary Markdown and stays valid, so uploading is offered
            // *alongside* by-URL rather than instead of it. One button with a menu, because both
            // are "insert an image" and two buttons would compete for the same icon.
            return (
              <span key="image">
                {button('image', t('markdown.image'), <ImageOutlined sx={iconSx} />, (e) =>
                  onPickFile ? setImageAnchor(e.currentTarget) : run((v) =>
                    insertImage(v, t('markdown.linkUrl'), t('markdown.imageAlt')))(),
                )}
                <Menu
                  anchorEl={imageAnchor}
                  open={Boolean(imageAnchor)}
                  onClose={() => setImageAnchor(null)}
                  // Same focus dance as the heading menu above, for the same reason: closing a Menu
                  // restores focus to whatever opened it, which lands after our own focus() call and
                  // leaves the caret on the toolbar button looking like it vanished.
                  disableRestoreFocus
                  TransitionProps={{ onExited: () => view?.focus() }}
                >
                  <MenuItem
                    onClick={() => {
                      setImageAnchor(null)
                      onPickFile?.()
                    }}
                  >
                    {t('markdown.uploadFile')}
                  </MenuItem>
                  <MenuItem
                    onClick={() => {
                      setImageAnchor(null)
                      if (view) insertImage(view, t('markdown.linkUrl'), t('markdown.imageAlt'))
                    }}
                  >
                    {t('markdown.imageByUrl')}
                  </MenuItem>
                </Menu>
              </span>
            )
          case 'codeBlock':
            return button('codeBlock', t('markdown.codeBlock'), <DataObjectOutlined sx={iconSx} />,
              run((v) => insertCodeBlock(v)))
          case 'table':
            return button('table', t('markdown.table'), <TableChartOutlined sx={iconSx} />,
              run((v) => insertTable(v, t('markdown.tableHeader'), t('markdown.tableCell'))))
          case 'rule':
            return button('rule', t('markdown.rule'), <HorizontalRuleOutlined sx={iconSx} />,
              run(insertRule))
          case 'math':
            // A menu rather than two buttons, for the same reason as the image one: both items are
            // "insert a formula" and would fight over the sigma. The placeholder is real TeX
            // (`x^2`) rather than a word, so the facing preview typesets something the moment the
            // button is pressed — which is how a teacher finds out the feature exists at all.
            return (
              <span key="math">
                {button('math', t('markdown.math'), <FunctionsOutlined sx={iconSx} />, (e) =>
                  setMathAnchor(e.currentTarget),
                )}
                <Menu
                  anchorEl={mathAnchor}
                  open={Boolean(mathAnchor)}
                  onClose={() => setMathAnchor(null)}
                  // Same focus dance as the heading and image menus, for the same reason.
                  disableRestoreFocus
                  TransitionProps={{ onExited: () => view?.focus() }}
                >
                  <MenuItem
                    onClick={() => {
                      setMathAnchor(null)
                      if (view) applyFormat(view, '$', '$', t('markdown.mathText'))
                    }}
                  >
                    {t('markdown.mathInline')}
                  </MenuItem>
                  <MenuItem
                    onClick={() => {
                      setMathAnchor(null)
                      if (view) insertMathBlock(view, t('markdown.mathText'))
                    }}
                  >
                    {t('markdown.mathDisplay')}
                  </MenuItem>
                </Menu>
              </span>
            )
        }
      })}

      {/*
        Last, and pushed as far right as the row allows: it changes how the text is displayed
        rather than what the text is, so it does not belong among the formatting commands. The
        spacer collapses to a gap in a narrow box — the comment editor wedged into someone's code
        — and opens up to the full width in the exercise text tab.

        Never disabled. `disabled` here means the document cannot be edited; how it is displayed
        is still the reader's business.
      */}
      <Box sx={{ flex: 1, minWidth: 8 }} />
      <Tooltip title={t('general.wrapLines')}>
        <IconButton
          size="small"
          onClick={toggleWrap}
          aria-label={t('general.wrapLines')}
          aria-pressed={wrap}
          sx={{
            ...btnSx,
            ...(wrap && { color: 'text.primary', bgcolor: 'action.selected' }),
          }}
        >
          <WrapTextOutlined sx={iconSx} />
        </IconButton>
      </Tooltip>
    </Box>
  )
}
