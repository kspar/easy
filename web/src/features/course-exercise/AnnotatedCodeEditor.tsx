import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react'
import { createPortal } from 'react-dom'
import {
  Box,
  Button,
  Checkbox,
  CircularProgress,
  FormControlLabel,
  IconButton,
  Tooltip,
  Typography,
} from '@mui/material'
import { DeleteOutlined, EditOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  EditorView,
  Decoration,
  WidgetType,
  ViewPlugin,
  gutter,
  GutterMarker,
  keymap,
  placeholder as cmPlaceholder,
} from '@codemirror/view'
import {
  EditorState,
  StateField,
  StateEffect,
  RangeSetBuilder,
} from '@codemirror/state'
import { basicSetup, minimalSetup } from 'codemirror'
import { markdown } from '@codemirror/lang-markdown'
import { oneDark } from '@codemirror/theme-one-dark'
import { useTheme } from '@mui/material/styles'
import { languageFromFilename } from './editorLanguage.ts'
import { useMarkdownPreview } from '../../api/exercises.ts'
import type { InlineCommentResp } from '../../api/types.ts'
import ConfirmDialog from '../participants/ConfirmDialog.tsx'
// Shared with the exercise text editor, which shows a superset of these buttons. Keeping one
// implementation is what stops the two editors formatting the same document differently.
import MarkdownToolbar from '../../components/markdown/MarkdownToolbar.tsx'
import RenderedMarkdown from '../../components/markdown/RenderedMarkdown.tsx'
import { useMarkdownUpload } from '../../components/markdown/useMarkdownUpload.ts'
import { useFileDropExtension } from '../../components/markdown/useFileDropExtension.ts'
import { COMPACT_TOOLS } from '../../components/markdown/markdownTools.ts'
import { applyFormat } from '../../components/markdown/markdownActions.ts'

/* ───────── Types ───────── */

export interface NewCommentData {
  line_start: number
  line_end: number
  code: string
  text_md: string
  suggested_code?: string
  notify_student?: boolean
}

interface DraftComment {
  lineStart: number
  lineEnd: number
  textMd: string
  suggestedCode: string
  notifyStudent: boolean
  /** Comment ID when editing, or null for new */
  editCommentId: string | null
}

interface Props {
  solution: string
  fileName: string
  comments: InlineCommentResp[]
  currentTeacherId?: string
  onCreateComment?: (data: NewCommentData) => Promise<void>
  onUpdateComment?: (commentId: string, data: NewCommentData) => Promise<void>
  onDeleteComment?: (commentId: string) => Promise<void>
}

/* ───────── CodeMirror plumbing ───────── */

interface AnnotationSnapshot {
  comments: InlineCommentResp[]
  draft: DraftComment | null
  editCommentId: string | null
}

interface PortalStore {
  containers: Map<string, HTMLElement>
}

const setAnnotationsEffect = StateEffect.define<AnnotationSnapshot>()

class BlockWidget extends WidgetType {
  // Fields declared and assigned explicitly — constructor parameter properties are
  // TS-only syntax, which `erasableSyntaxOnly` forbids.
  readonly key: string
  readonly store: PortalStore

  constructor(key: string, store: PortalStore) {
    super()
    this.key = key
    this.store = store
  }

  toDOM() {
    const div = document.createElement('div')
    this.store.containers.set(this.key, div)
    return div
  }

  destroy(dom: HTMLElement) {
    if (this.store.containers.get(this.key) === dom) {
      this.store.containers.delete(this.key)
    }
  }

  eq(other: BlockWidget) {
    return this.key === other.key
  }

  get estimatedHeight() {
    return this.key === 'draft' ? 120 : 60
  }

  ignoreEvent() {
    return true
  }
}

function buildDecorations(
  state: EditorState,
  snap: AnnotationSnapshot,
  store: PortalStore,
) {
  const { comments, draft, editCommentId } = snap
  const builder = new RangeSetBuilder<Decoration>()
  const totalLines = state.doc.lines

  const annotatedLines = new Set<number>()
  for (const c of comments) {
    for (let i = c.line_start; i <= c.line_end; i++) annotatedLines.add(i)
  }

  const afterLine = new Map<number, { key: string }[]>()

  if (draft) {
    const arr = afterLine.get(draft.lineEnd) ?? []
    arr.push({ key: 'draft' })
    afterLine.set(draft.lineEnd, arr)
  }
  for (const c of comments) {
    if (c.id === editCommentId) continue
    const arr = afterLine.get(c.line_end) ?? []
    arr.push({ key: `comment-${c.id}` })
    afterLine.set(c.line_end, arr)
  }

  for (let lineNum = 1; lineNum <= totalLines; lineNum++) {
    const line = state.doc.line(lineNum)

    if (annotatedLines.has(lineNum)) {
      builder.add(line.from, line.from, Decoration.line({ class: 'cm-annotated-line' }))
    }

    const widgets = afterLine.get(lineNum)
    if (widgets) {
      for (const w of widgets) {
        builder.add(
          line.to,
          line.to,
          Decoration.widget({
            widget: new BlockWidget(w.key, store),
            block: true,
            side: 1,
          }),
        )
      }
    }
  }

  return builder.finish()
}

class AddCommentMarker extends GutterMarker {
  toDOM() {
    const span = document.createElement('span')
    span.className = 'cm-add-comment-icon'
    span.textContent = '+'
    return span
  }
}

const addMarker = new AddCommentMarker()

function lineHoverPlugin() {
  return ViewPlugin.fromClass(class {
    private lastHovered: HTMLElement | null = null
    readonly view: EditorView

    constructor(view: EditorView) {
      this.view = view
      view.dom.addEventListener('mousemove', this.onMove as EventListener)
      view.dom.addEventListener('mouseleave', this.onLeave as EventListener)
    }

    onMove = (e: MouseEvent) => {
      const col = this.view.dom.querySelector('.cm-add-comment-gutter')
      if (!col) return

      // Find the gutter element at this Y position
      const els = col.querySelectorAll<HTMLElement>('.cm-gutterElement')
      let match: HTMLElement | null = null
      for (const el of els) {
        const r = el.getBoundingClientRect()
        if (e.clientY >= r.top && e.clientY < r.bottom) { match = el; break }
      }
      if (match !== this.lastHovered) {
        this.lastHovered?.classList.remove('gutter-hovered')
        match?.classList.add('gutter-hovered')
        this.lastHovered = match
      }
    }

    onLeave = () => {
      this.lastHovered?.classList.remove('gutter-hovered')
      this.lastHovered = null
    }

    destroy() {
      this.view.dom.removeEventListener('mousemove', this.onMove as EventListener)
      this.view.dom.removeEventListener('mouseleave', this.onLeave as EventListener)
      this.lastHovered?.classList.remove('gutter-hovered')
    }
  })
}

/* ───────── Component ───────── */

export default function AnnotatedCodeEditor({
  solution,
  fileName,
  comments,
  currentTeacherId,
  onCreateComment,
  onUpdateComment,
  onDeleteComment,
}: Props) {
  const { t } = useTranslation()
  const theme = useTheme()
  const editorContainerRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)

  const [draft, setDraft] = useState<DraftComment | null>(null)
  const [saving, setSaving] = useState(false)
  const [portalVersion, setPortalVersion] = useState(0)
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null)

  const commentsRef = useRef(comments)
  commentsRef.current = comments
  const draftRef = useRef(draft)
  draftRef.current = draft
  const portalStoreRef = useRef<PortalStore>({ containers: new Map() })
  const addCommentRef = useRef<(line: number) => void>(() => {})

  const canEdit = !!onCreateComment

  /* ── Callbacks ── */

  const handleAddComment = useCallback((lineNum: number) => {
    let notify = true
    try { notify = localStorage.getItem('teacherNotifyInline') !== 'false' } catch { /* ignore */ }
    setDraft({
      lineStart: lineNum,
      lineEnd: lineNum,
      textMd: '',
      suggestedCode: '',
      notifyStudent: notify,
      editCommentId: null,
    })
  }, [])

  const handleEditComment = useCallback((comment: InlineCommentResp) => {
    let notify = true
    try { notify = localStorage.getItem('teacherNotifyInline') !== 'false' } catch { /* ignore */ }
    setDraft({
      lineStart: comment.line_start,
      lineEnd: comment.line_end,
      textMd: comment.text_md,
      suggestedCode: comment.suggested_code ?? '',
      notifyStudent: notify,
      editCommentId: comment.id,
    })
  }, [])

  const handleDeleteComment = useCallback((commentId: string) => {
    setConfirmDeleteId(commentId)
  }, [])

  const handleConfirmDelete = useCallback(async () => {
    if (!onDeleteComment || !confirmDeleteId) return
    setSaving(true)
    try {
      await onDeleteComment(confirmDeleteId)
    } finally {
      setSaving(false)
      setConfirmDeleteId(null)
    }
  }, [onDeleteComment, confirmDeleteId])

  const handleSaveDraft = useCallback(async (textMd: string, notifyStudent: boolean) => {
    const d = draftRef.current
    const view = viewRef.current
    if (!d || !textMd.trim() || !view) return

    const doc = view.state.doc
    const fromLine = doc.line(d.lineStart)
    const toLine = doc.line(d.lineEnd)
    const code = doc.sliceString(fromLine.from, toLine.to)

    const data: NewCommentData = {
      line_start: d.lineStart,
      line_end: d.lineEnd,
      code,
      text_md: textMd,
      // This used to also send `type: d.suggestedCode ? 'suggestion' : 'comment'`, which is why
      // EZ-1777 ended in deleting the field rather than validating it: the line below already says
      // it, and two statements of one fact can disagree.
      ...(d.suggestedCode ? { suggested_code: d.suggestedCode } : {}),
      notify_student: notifyStudent,
    }

    setSaving(true)
    try {
      if (d.editCommentId && onUpdateComment) {
        await onUpdateComment(d.editCommentId, data)
      } else if (onCreateComment) {
        await onCreateComment(data)
      }
      setDraft(null)
    } finally {
      setSaving(false)
    }
  }, [onCreateComment, onUpdateComment])

  const handleCancelDraft = useCallback(() => {
    setDraft(null)
  }, [])

  addCommentRef.current = handleAddComment

  /* ── Initialise CodeMirror ── */

  useEffect(() => {
    if (!editorContainerRef.current) return
    let cancelled = false

    portalStoreRef.current.containers.clear()
    viewRef.current?.destroy()
    viewRef.current = null

    const store = portalStoreRef.current

    languageFromFilename(fileName).then((lang) => {
      if (cancelled || !editorContainerRef.current) return

      const annotationField = StateField.define({
        create: () => Decoration.none,
        update(decos, tr) {
          for (const e of tr.effects) {
            if (e.is(setAnnotationsEffect)) {
              return buildDecorations(tr.state, e.value, store)
            }
          }
          return decos.map(tr.changes)
        },
        provide: (f) => EditorView.decorations.from(f),
      })

      const addCommentGutter = canEdit ? gutter({
        class: 'cm-add-comment-gutter',
        lineMarker: () => addMarker,
        domEventHandlers: {
          click: (view, line) => {
            addCommentRef.current(view.state.doc.lineAt(line.from).number)
            return true
          },
        },
      }) : []

      const isDark = theme.palette.mode === 'dark'

      const editorTheme = EditorView.theme({
        '.cm-content': { paddingTop: '4px' },
        '.cm-annotated-line': {
          backgroundColor: isDark
            ? 'rgba(255, 167, 38, 0.08)'
            : 'rgba(255, 167, 38, 0.06)',
        },
        '.cm-add-comment-gutter .cm-gutterElement': {
          cursor: 'pointer',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: '28px',
        },
        '.cm-add-comment-icon': {
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: '22px',
          height: '22px',
          borderRadius: '50%',
          opacity: '0',
          transform: 'scale(0.7)',
          transition: 'opacity 0.15s, transform 0.15s, background-color 0.15s',
          fontSize: '16px',
          fontWeight: 'bold',
          lineHeight: '1',
          color: theme.palette.primary.main,
          userSelect: 'none',
        },
        '.cm-add-comment-gutter .cm-gutterElement.gutter-hovered .cm-add-comment-icon': {
          opacity: '1',
          transform: 'scale(1)',
        },
        '.cm-add-comment-gutter .cm-gutterElement.gutter-hovered .cm-add-comment-icon:hover': {
          backgroundColor: isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)',
          transform: 'scale(1.15)',
        },
        '.cm-add-comment-gutter .cm-gutterElement.gutter-hovered .cm-add-comment-icon:active': {
          transform: 'scale(0.9)',
          backgroundColor: isDark ? 'rgba(255,255,255,0.15)' : 'rgba(0,0,0,0.12)',
          transition: 'transform 0.05s',
        },
        '&.has-draft .cm-add-comment-gutter .cm-gutterElement': {
          pointerEvents: 'none',
        },
        '&.has-draft .cm-add-comment-icon': {
          display: 'none',
        },
        '&.has-draft .cm-lineNumbers .cm-gutterElement': {
          cursor: 'default',
        },
      })

      const extensions = [
        ...(Array.isArray(addCommentGutter) ? addCommentGutter : [addCommentGutter]),
        basicSetup,
        lang,
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
        EditorView.lineWrapping,
        annotationField,
        ...(canEdit ? [lineHoverPlugin()] : []),
        editorTheme,
      ]
      if (isDark) extensions.push(oneDark)

      const state = EditorState.create({ doc: solution, extensions })

      viewRef.current = new EditorView({
        state,
        parent: editorContainerRef.current,
      })

      viewRef.current.dispatch({
        effects: setAnnotationsEffect.of({
          comments: commentsRef.current,
          draft: draftRef.current,
          editCommentId: draftRef.current?.editCommentId ?? null,
        }),
      })
      setPortalVersion((v) => v + 1)
    })

    return () => {
      cancelled = true
      viewRef.current?.destroy()
      viewRef.current = null
      portalStoreRef.current.containers.clear()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme.palette.mode, fileName, solution, canEdit])

  /* ── Sync React state → CodeMirror ── */

  useLayoutEffect(() => {
    const view = viewRef.current
    if (!view) return
    view.dispatch({
      effects: setAnnotationsEffect.of({
        comments,
        draft,
        editCommentId: draft?.editCommentId ?? null,
      }),
    })
    setPortalVersion((v) => v + 1)
  }, [comments, draft])

  /* ── Toggle gutter visibility when draft is open ── */

  useLayoutEffect(() => {
    viewRef.current?.dom.classList.toggle('has-draft', draft !== null)
  }, [draft])

  /* ── Build portals ── */

  void portalVersion
  const portals: React.ReactNode[] = []
  for (const [key, container] of portalStoreRef.current.containers) {
    if (key === 'draft' && draft) {
      portals.push(
        createPortal(
          <CommentEditor
            draft={draft}
            onSave={handleSaveDraft}
            onCancel={handleCancelDraft}
            saving={saving}
            t={t}
          />,
          container,
          'draft',
        ),
      )
    } else if (key.startsWith('comment-')) {
      const commentId = key.slice('comment-'.length)
      const comment = comments.find((c) => c.id === commentId)
      if (comment) {
        const isOwn = !!currentTeacherId && comment.teacher.id === currentTeacherId
        portals.push(
          createPortal(
            <CommentCard
              comment={comment}
              canEdit={isOwn && !!onUpdateComment}
              canDelete={isOwn && !!onDeleteComment}
              onEdit={() => handleEditComment(comment)}
              onDelete={() => handleDeleteComment(comment.id)}
              t={t}
            />,
            container,
            key,
          ),
        )
      }
    }
  }

  return (
    <Box>
      {/* File name header */}
      <Box sx={{
        display: 'flex',
        alignItems: 'center',
        px: 1.5,
        py: 0.5,
        border: 1,
        borderBottom: 0,
        borderColor: 'divider',
        borderRadius: '4px 4px 0 0',
        bgcolor: (th) => th.palette.mode === 'dark' ? '#282c34' : '#f5f5f5',
      }}>
        <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
          {fileName}
        </Typography>
      </Box>

      {/* CodeMirror editor */}
      <Box
        ref={editorContainerRef}
        sx={{
          border: 1,
          borderColor: 'divider',
          borderRadius: '0 0 4px 4px',
          overflow: 'hidden',
          '& .cm-editor': { cursor: 'default' },
          '& .cm-focused': { outline: 'none' },
        }}
      />

      {/* React portals rendered into CodeMirror widget containers */}
      {portals}

      <ConfirmDialog
        open={!!confirmDeleteId}
        message={t('submission.confirmDeleteComment')}
        confirmLabel={t('general.delete')}
        confirmColor="error"
        isPending={saving}
        onClose={() => setConfirmDeleteId(null)}
        onConfirm={handleConfirmDelete}
      />
    </Box>
  )
}

/* ───────── Sub-components ───────── */

function CommentCard({
  comment,
  canEdit,
  canDelete,
  onEdit,
  onDelete,
  t,
}: {
  comment: InlineCommentResp
  canEdit: boolean
  canDelete: boolean
  onEdit: () => void
  onDelete: () => void
  t: (key: string) => string
}) {
  return (
    <Box
      sx={{
        py: 1,
        px: 1.5,
        bgcolor: (th) =>
          th.palette.mode === 'dark'
            ? 'rgba(255,255,255,0.04)'
            : 'rgba(0,0,0,0.025)',
        borderTop: 1,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.25 }}>
            {comment.teacher.given_name} {comment.teacher.family_name}
          </Typography>
          <Typography variant="body2" sx={{ fontSize: '0.85rem', whiteSpace: 'pre-wrap' }}>
            {comment.text_md}
          </Typography>
          {comment.suggested_code && (
            <Box sx={{ mt: 1 }}>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
                {t('submission.suggestedChange')}
              </Typography>
              <Box
                component="pre"
                sx={{
                  m: 0,
                  p: 1,
                  bgcolor: (th) =>
                    th.palette.mode === 'dark'
                      ? 'rgba(46, 125, 50, 0.10)'
                      : 'rgba(46, 125, 50, 0.06)',
                  borderRadius: 0.5,
                  fontSize: '0.8rem',
                  fontFamily: 'monospace',
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                }}
              >
                {comment.suggested_code}
              </Box>
            </Box>
          )}
        </Box>

        {(canEdit || canDelete) && (
          <Box sx={{ display: 'flex', gap: 0.25, flexShrink: 0 }}>
            {canEdit && (
              <Tooltip title={t('submission.editComment')}>
                <IconButton size="small" onClick={onEdit} sx={{ p: 0.25 }}>
                  <EditOutlined sx={{ fontSize: 16, color: 'text.secondary' }} />
                </IconButton>
              </Tooltip>
            )}
            {canDelete && (
              <Tooltip title={t('submission.deleteComment')}>
                <IconButton size="small" onClick={onDelete} sx={{ p: 0.25 }}>
                  <DeleteOutlined sx={{ fontSize: 16, color: 'text.secondary' }} />
                </IconButton>
              </Tooltip>
            )}
          </Box>
        )}
      </Box>
    </Box>
  )
}

function CommentEditor({
  draft,
  onSave,
  onCancel,
  saving,
  t,
}: {
  draft: DraftComment
  onSave: (textMd: string, notifyStudent: boolean) => void
  onCancel: () => void
  saving: boolean
  t: (key: string) => string
}) {
  const theme = useTheme()
  const containerRef = useRef<HTMLDivElement>(null)
  const innerViewRef = useRef<EditorView | null>(null)
  // The toolbar is a child component, so it needs the view as a prop — and a ref assignment does
  // not re-render, which would leave every button permanently disabled.
  const [toolbarView, setToolbarView] = useState<EditorView | null>(null)

  const { uploadFiles, uploading, error: uploadError, clearError } = useMarkdownUpload()
  const dropExtension = useFileDropExtension(
    useCallback((files: File[]) => {
      if (innerViewRef.current) void uploadFiles(innerViewRef.current, files)
    }, [uploadFiles]),
  )
  const [text, setText] = useState(draft.textMd)
  const textRef = useRef(draft.textMd)
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const previewHtml = useMarkdownPreview(text, 150)
  const [notify, setNotify] = useState(draft.notifyStudent)

  // Persist notify preference
  useEffect(() => {
    try { localStorage.setItem('teacherNotifyInline', String(notify)) } catch { /* ignore */ }
  }, [notify])

  // Refs for stable keymap closures
  const onSaveRef = useRef(onSave)
  onSaveRef.current = onSave
  const onCancelRef = useRef(onCancel)
  onCancelRef.current = onCancel
  const notifyRef = useRef(notify)
  notifyRef.current = notify
  const savingRef = useRef(saving)
  savingRef.current = saving

  useEffect(() => {
    if (!containerRef.current) return

    const extensions = [
      minimalSetup,
      markdown(),
      EditorView.lineWrapping,
      cmPlaceholder(t('submission.feedbackPlaceholder')),
      keymap.of([
        { key: 'Mod-b', run: (v) => { applyFormat(v, '**', '**', 'bold'); return true } },
        { key: 'Mod-i', run: (v) => { applyFormat(v, '_', '_', 'italic'); return true } },
        { key: 'Mod-Enter', run: () => {
          if (textRef.current.trim() && !savingRef.current) onSaveRef.current(textRef.current, notifyRef.current)
          return true
        }},
        { key: 'Escape', run: () => { onCancelRef.current(); return true } },
      ]),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          textRef.current = update.state.doc.toString()
          clearTimeout(timerRef.current)
          timerRef.current = setTimeout(() => setText(textRef.current), 150)
        }
      }),
      EditorView.theme({
        '.cm-content': { padding: '8px 12px', fontSize: '0.875rem' },
        '&.cm-focused': { outline: 'none' },
      }),
      // Paste and drop only. COMPACT_TOOLS has no image button on purpose — this editor is wedged
      // between two lines of someone's code and every button costs width there — but an annotated
      // screenshot is a natural thing to attach to a review comment, and a paste costs nothing.
      ...(dropExtension ?? []),
    ]
    if (theme.palette.mode === 'dark') extensions.push(oneDark)

    const view = new EditorView({
      state: EditorState.create({ doc: draft.textMd, extensions }),
      parent: containerRef.current,
    })
    innerViewRef.current = view
    setToolbarView(view)

    view.focus()
    view.dispatch({ selection: { anchor: view.state.doc.length } })

    return () => {
      view.destroy()
      innerViewRef.current = null
      setToolbarView(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme.palette.mode, dropExtension])

  return (
    <Box
      sx={{
        bgcolor: (th) =>
          th.palette.mode === 'dark'
            ? 'rgba(33, 150, 243, 0.08)'
            : 'rgba(33, 150, 243, 0.04)',
        borderTop: 1,
        borderBottom: 1,
        borderColor: 'primary.main',
      }}
    >
      <Box sx={{ px: 0.75, py: 0.5, borderBottom: 1, borderColor: 'divider' }}>
        <MarkdownToolbar view={toolbarView} tools={COMPACT_TOOLS} />
        {uploading && <CircularProgress size={14} sx={{ ml: 0.5 }} />}
        {uploadError && (
          <Typography variant="caption" color="error" sx={{ ml: 1 }} onClick={clearError}>
            {uploadError}
          </Typography>
        )}
      </Box>

      {/* CodeMirror editor */}
      <Box
        ref={containerRef}
        sx={{
          '& .cm-editor': { minHeight: 40, cursor: 'text' },
          '& .cm-scroller': { maxHeight: 200, overflow: 'auto' },
        }}
      />

      {/* Markdown preview */}
      {previewHtml && (
        <RenderedMarkdown
          sx={{
            borderTop: 1,
            borderColor: 'divider',
            px: 2,
            py: 1,
            '& p:first-of-type': { mt: 0 },
            '& p:last-of-type': { mb: 0 },
            fontSize: '0.85rem',
          }}
          html={previewHtml}
        />
      )}

      {/* Actions */}
      <Box sx={{ display: 'flex', gap: 0.5, px: 1, py: 0.75, borderTop: 1, borderColor: 'divider', alignItems: 'center', flexWrap: 'wrap' }}>
        <Button
          size="small"
          variant="contained"
          onClick={() => onSave(textRef.current, notify)}
          disabled={!text.trim() || saving}
          sx={{ textTransform: 'none', fontSize: '0.75rem', minWidth: 0, px: 1.5, py: 0.25 }}
        >
          {saving ? <CircularProgress size={16} /> : t('general.save')}
        </Button>
        <Button
          size="small"
          variant="text"
          onClick={onCancel}
          disabled={saving}
          sx={{ textTransform: 'none', fontSize: '0.75rem', minWidth: 0, px: 1, py: 0.25 }}
        >
          {t('general.cancel')}
        </Button>
        <FormControlLabel
          control={
            <Checkbox
              checked={notify}
              onChange={(e) => setNotify(e.target.checked)}
              size="small"
            />
          }
          label={<Typography variant="body2">{t('submission.notifyStudent')}</Typography>}
          sx={{ ml: 'auto', mr: 0 }}
        />
      </Box>
    </Box>
  )
}
