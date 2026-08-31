import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  IconButton,
  MenuItem,
  Switch,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material'
import { CloseOutlined, ContentCopyOutlined } from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import {
  useLibraryExercise,
  useSetExerciseEmbed,
  useSetExerciseTemplate,
} from '../../api/library.ts'
import CodeEditor from '../../components/CodeEditor.tsx'
import { hasAccess, slugify } from './links.ts'
import useEmbedOptions from '../../hooks/useEmbedOptions.ts'

/**
 * Parent-side auto-resizer, served by this app from `public/`. Published embeds already carry a
 * script tag pointing at this path, so it stays where wui put it.
 */
const RESIZER_SCRIPT_PATH = '/static/js/ez-embed-frame-resizer.js'

/**
 * Embed snippets for the anonymous auto-assessment view.
 *
 * The generated URL follows wui's current scheme — plural `exercises`, and valueless *negative*
 * flags rather than `showX=true`. Not a style choice: it is what `EmbedExercisePage` reads, which
 * in turn is what production reads, so a snippet generated here behaves the same as the thousands
 * of characters of embed HTML already pasted into PmWiki pages nobody here can edit.
 *
 * This generator previously got all three parts of that wrong — singular `exercise` in the path,
 * `showTitle=true&showSubmit=…` parameters the page never looked at (so "allow testing" silently
 * did nothing), and an `@iframe-resizer/child` script from a CDN, which is the wrong half of the
 * wrong library for a protocol the page does not speak.
 */
export default function EmbedDialog({
  exerciseId,
  currentCourseId,
  currentCourseExerciseId,
  open,
  onClose,
}: {
  exerciseId: string
  /**
   * Set when the dialog is opened from a course exercise rather than the library. That course is
   * preselected and marked in the link dropdown, and the starting code's reach is spelled out more
   * loudly — from here it is least obvious that editing it touches every other course too.
   */
  currentCourseId?: string
  currentCourseExerciseId?: string
  open: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()

  // Fetched here rather than passed in: the course exercise page has none of this — its own
  // response carries neither the embed flag nor the template — and wui's modal did the same. On
  // the library page the query is already warm, so this costs nothing there.
  const { data: exercise, isLoading } = useLibraryExercise(open ? exerciseId : undefined)

  const setEmbed = useSetExerciseEmbed(exerciseId)
  const saveTemplate = useSetExerciseTemplate(exerciseId)

  const exerciseTitle = exercise?.title ?? ''
  const template = exercise?.anonymous_autoassess_template ?? ''
  const embedEnabled = exercise?.is_anonymous_autoassess_enabled ?? false
  const canEdit = exercise != null && hasAccess(exercise.effective_access, 'PRAW')
  const isAutoAssessable = exercise?.grader_type === 'AUTO'

  // The course this was opened from goes first and is marked, because from a course page that is
  // almost always the link you want.
  const onCourses = useMemo(() => {
    const all = exercise?.on_courses ?? []
    if (!currentCourseExerciseId) return all
    const here = all.filter((c) => c.course_exercise_id === currentCourseExerciseId)
    return [...here, ...all.filter((c) => c.course_exercise_id !== currentCourseExerciseId)]
  }, [exercise?.on_courses, currentCourseExerciseId])

  // Remembered across dialogs: embedding a run of exercises into one page means opening this
  // repeatedly and wanting the same answers. The two exercise-specific fields below are not.
  const [options, setOptions] = useEmbedOptions()
  const { showTitle, allowTesting, format } = options
  const [titleAlias, setTitleAlias] = useState('')
  const [copied, setCopied] = useState(false)

  // The link toggle emits nothing of its own — the URL only ever carries `course`/`exercise`, and
  // only when one is picked. It exists so the dropdown reads like the switches above it, and so
  // there is somewhere obvious to turn the link off. Defaults on from a course page, where a
  // course is already known; off from the library, where there is nothing to preselect.
  const [linkEnabled, setLinkEnabled] = useState(Boolean(currentCourseExerciseId))
  const [linkCourse, setLinkCourse] = useState(currentCourseExerciseId ?? '')

  function toggleLink(on: boolean) {
    setLinkEnabled(on)
    setLinkCourse(on ? (currentCourseExerciseId ?? '') : '')
  }

  // The preview sizes itself from the same `ez-frame-resize` message a real embed uses, so it
  // never scrolls inside its own box and the dialog does the scrolling — and the protocol gets
  // exercised every time anyone opens this.
  const [previewHeight, setPreviewHeight] = useState(320)

  // Reflects whether the exercise *has* starting code, rather than being a snippet flag. Switching
  // it off clears the stored template; switching it on just opens an empty editor to type into.
  // Seeded once from the loaded exercise — after that it is the user's, and must not flip itself
  // off the moment they clear the editor by hand.
  const [templateEnabled, setTemplateEnabled] = useState(false)
  const templateSeeded = useRef(false)
  useEffect(() => {
    if (!exercise || templateSeeded.current) return
    templateSeeded.current = true
    setTemplateEnabled(exercise.anonymous_autoassess_template !== '')
  }, [exercise])

  function toggleTemplate(on: boolean) {
    setTemplateEnabled(on)
    // Off means the exercise has no starting code; the autosave below writes that through.
    if (!on) setTemplateDraft('')
  }

  // The template autosaves, so there are two things to keep straight: what the server last told us
  // (`template`), and what we last sent it (`lastSaved`). Re-seeding the editor from the prop
  // unconditionally would fight the user — every save triggers a refetch, and a refetch landing
  // mid-keystroke would rewind the cursor to whatever was saved a second ago.
  const [templateDraft, setTemplateDraft] = useState(template)
  const lastSaved = useRef(template)
  const [previewNonce, setPreviewNonce] = useState(0)

  useEffect(() => {
    if (template === lastSaved.current) return
    // Changed underneath us — someone else's edit, or the first load. Take it.
    lastSaved.current = template
    setTemplateDraft(template)
  }, [template])

  const saveRef = useRef(saveTemplate)
  saveRef.current = saveTemplate

  useEffect(() => {
    if (!canEdit || templateDraft === lastSaved.current) return
    const timer = setTimeout(() => {
      lastSaved.current = templateDraft
      // The preview iframe loads the embed page, which reads the template at mount — so it has to
      // be remounted to show the change. The src is untouched; only the React key moves.
      saveRef.current.mutate(templateDraft, { onSuccess: () => setPreviewNonce((n) => n + 1) })
    }, 700)
    return () => clearTimeout(timer)
  }, [templateDraft, canEdit])

  const templatePending = saveTemplate.isPending || templateDraft !== lastSaved.current

  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      // The only sender we want is the preview iframe below, whose `src` is built from
      // `window.location.origin` — so comparing against that origin is right on every deployment
      // without naming a domain anywhere. A hardcoded host would have to be wrong on dev or on
      // production, and a config key would be a second place for the same fact to live.
      //
      // Without this, anything able to post into this window could set the preview's height: a frame
      // it embeds, a window it opened, or its opener. The ceiling is cosmetic today — the handler's
      // whole effect is `setPreviewHeight(number)` — and the reachable senders are our own pages.
      // It is checked anyway because an unvalidated `message` handler stops being cosmetic the moment
      // somebody adds a second `msg.type`, and by then the missing check is not what they are
      // thinking about.
      if (e.origin !== window.location.origin) return
      try {
        const msg = JSON.parse(String(e.data))
        if (msg?.type === 'ez-frame-resize' && typeof msg.height === 'number' && msg.height > 0) {
          setPreviewHeight(msg.height)
        }
      } catch {
        // Not one of ours — the page shares this window with anything else that posts messages.
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [])

  // Only the non-default flags appear, which is what keeps a plain embed's URL short and is also
  // how the page reads them: presence, not value. `dynamicResize` is deliberately not an option —
  // it is what the resizer script in the snippet talks to, so switching it off would leave the
  // script loaded and mute.
  // Neither `no-border` nor `no-template` is ever emitted now. Border is not an option at all,
  // and "no starting code" is expressed by the exercise simply not having one — a stored empty
  // string — rather than by a parameter every snippet would have to carry. The embed page still
  // honours both, because embeds published while they were options are still out there.
  const flags = [
    !showTitle && 'no-title',
    allowTesting && 'submit',
  ].filter(Boolean) as string[]

  // title-alias and the course link carry values, so they are appended after the bare flags.
  const course = onCourses.find((c) => c.course_exercise_id === linkCourse)
  const params = [
    ...flags,
    titleAlias.trim() && `title-alias=${encodeURIComponent(titleAlias.trim())}`,
    course && `course=${encodeURIComponent(course.id)}`,
    course && `exercise=${encodeURIComponent(course.course_exercise_id)}`,
  ].filter(Boolean) as string[]
  const query = params.length ? `?${params.join('&')}` : ''

  // `slugify`, not `encodeURIComponent` — the third thing this generator got wrong about wui's
  // scheme, alongside the two above. The parent-side resizer script finds the iframe by matching
  // `decodeURI(<the url the frame reports>)` against the `src` attribute, so a percent-encoded path
  // decodes to something the attribute never said and no iframe is ever found: the embed keeps its
  // 150px default and the exercise is cut off (EZ-1831). A readable slug survives that round trip.
  const origin = window.location.origin
  const src =
    `${origin}/embed/exercises/${exerciseId}/` +
    `${slugify(exerciseTitle)}${query}`

  const html =
    `<script src="${origin}${RESIZER_SCRIPT_PATH}"></script>\n` +
    `<iframe src="${src}" width="100%" style="border: none;"></iframe>`
  const snippet = format === 'html' ? html : `(:html:)\n${html}\n(:htmlend:)`

  async function copy() {
    await navigator.clipboard.writeText(snippet)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Box component="span" sx={{ flexGrow: 1 }}>
          {t('library.embedding')}
        </Box>
        {/* Closing lives here rather than in a footer button: nothing in this dialog is pending,
            and the footer is now where Copy would have competed with it. */}
        <IconButton aria-label={t('general.close')} onClick={onClose} size="small">
          <CloseOutlined />
        </IconButton>
      </DialogTitle>
      <DialogContent>
        {isLoading && (
          <Box display="flex" justifyContent="center" py={3}>
            <CircularProgress size={24} />
          </Box>
        )}
        {exercise && (
          <>
        <FormControlLabel
          control={
            <Switch
              checked={embedEnabled}
              disabled={!canEdit || setEmbed.isPending}
              onChange={(e) => setEmbed.mutate(e.target.checked)}
              // The visible label reads "Enabled"/"Disabled", which names the state rather than
              // the control, so screen readers get the purpose from here instead. `role` is
              // repeated because slotProps.input replaces MUI's own input props, and dropping it
              // would silently demote the switch to a plain checkbox.
              slotProps={{ input: { 'aria-label': t('library.embedToggle'), role: 'switch' } }}
            />
          }
          label={embedEnabled ? t('general.enabled') : t('general.disabled')}
        />
        {!canEdit && !embedEnabled && (
          <Alert severity="info" sx={{ mt: 1 }}>
            {t('library.embedNoEditAccess')}
          </Alert>
        )}

        {embedEnabled && (
          <Box mt={2}>
            {/*
              Everything from here down shapes the snippet you copy and is forgotten when you
              close — unlike the switch above, which writes to the exercise. Saying so is the
              cheapest way to stop the two reading as one undifferentiated pile of toggles. The
              starting code is the exception, and labels itself as one.
            */}
            <Typography variant="overline" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
              {t('library.embedSnippetOptions')}
            </Typography>
            {/*
              One row per option, each pairing the switch with the field it governs. Border is
              gone entirely — every embed is bordered now.
            */}
            <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 2, mt: 1 }}>
              <FormControlLabel
                sx={{ minWidth: '11rem', mt: 1 }}
                control={<Switch checked={showTitle} onChange={(e) => setOptions({ showTitle: e.target.checked })} />}
                label={t('library.embedShowTitle')}
              />
              <TextField
                label={t('library.embedTitleAlias')}
                value={titleAlias}
                onChange={(e) => setTitleAlias(e.target.value)}
                size="small"
                sx={{ flex: 1 }}
                helperText={t('library.embedTitleAliasHint')}
                disabled={!showTitle}
              />
            </Box>

            <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 2, mt: 1 }}>
              <FormControlLabel
                sx={{ minWidth: '11rem', mt: 1 }}
                control={<Switch checked={linkEnabled} onChange={(e) => toggleLink(e.target.checked)} />}
                label={t('library.embedLinkCourse')}
              />
              <TextField
                select
                label={t('library.embedCourse')}
                value={linkCourse}
                onChange={(e) => setLinkCourse(e.target.value)}
                size="small"
                sx={{ flex: 1 }}
                helperText={
                  onCourses.length ? t('library.embedLinkCourseHint') : t('library.embedNotOnCourses')
                }
                disabled={!linkEnabled || onCourses.length === 0}
              >
                <MenuItem value="">{t('library.embedNoLink')}</MenuItem>
                {onCourses.map((c) => (
                  <MenuItem key={c.course_exercise_id} value={c.course_exercise_id}>
                    {c.alias ?? c.title}
                    {c.course_exercise_id === currentCourseExerciseId && (
                      <Typography component="span" variant="caption" color="text.secondary" sx={{ ml: 1 }}>
                        {t('library.embedThisCourse')}
                      </Typography>
                    )}
                  </MenuItem>
                ))}
              </TextField>
            </Box>

            {isAutoAssessable && (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mt: 1 }}>
                <FormControlLabel
                  sx={{ minWidth: '11rem' }}
                  control={<Switch checked={allowTesting} onChange={(e) => setOptions({ allowTesting: e.target.checked })} />}
                  label={t('library.embedAllowTesting')}
                />
                <FormControlLabel
                  control={
                    <Switch
                      checked={templateEnabled}
                      // The editor it fills only exists when testing is on, so on its own this
                      // would change nothing visible.
                      disabled={!allowTesting || !canEdit}
                      onChange={(e) => toggleTemplate(e.target.checked)}
                    />
                  }
                  label={t('library.embedShowTemplate')}
                />
              </Box>
            )}



            {/*
              Sits under the toggle that governs it and disappears with it. It is also the only
              thing in this dialog that writes to the database — everything else here shapes the
              snippet — which is why it saves itself and says so.
            */}
            {templateEnabled && allowTesting && (
              // Indented under its switch and fenced off, so it reads as belonging to that one
              // option rather than as another section of the dialog.
              <Box
                sx={{
                  mt: 1,
                  mb: 1,
                  ml: '11rem',
                  p: 1.5,
                  border: '1px dashed',
                  borderColor: 'divider',
                  borderRadius: 1,
                }}
              >
                {currentCourseId && (
                  <Alert severity="info" sx={{ mb: 1 }}>
                    {t('library.embedTemplateSharedWarning')}
                  </Alert>
                )}
                <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1, mb: 0.5 }}>
                  <Typography variant="caption" color="text.secondary" sx={{ flexGrow: 1 }}>
                    {t('library.embedTemplateHint')}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" aria-live="polite">
                    {templatePending
                      ? t('general.saving')
                      : saveTemplate.isSuccess
                        ? t('general.saved')
                        : ''}
                  </Typography>
                </Box>
                <CodeEditor
                  ariaLabel={t('library.embedTemplate')}
                  value={templateDraft}
                  onChange={setTemplateDraft}
                  readOnly={!canEdit}
                  minHeight="6rem"
                  lineNumbers={false}
                  placeholder={t('library.embedTemplatePlaceholder')}
                />
                {saveTemplate.isError && (
                  <Alert severity="error" sx={{ mt: 1 }}>
                    {t('library.embedTemplateSaveFailed')}
                  </Alert>
                )}
              </Box>
            )}

            <Tabs
              value={format}
              onChange={(_, v) => setOptions({ format: v })}
              sx={{ minHeight: 36, mt: 2, mb: 1, '& .MuiTab-root': { minHeight: 36, textTransform: 'none' } }}
            >
              <Tab value="html" label="HTML" />
              <Tab value="pmwiki" label="PmWiki" />
            </Tabs>
            <CodeEditor ariaLabel={t('library.embedSnippet')} value={snippet} readOnly minHeight="8rem" lineNumbers={false} />
            {/* Directly under what it copies, at the start of the line the eye returns to after
                reading the snippet — rather than in a footer, where it was easy to miss. */}
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mt: 1 }}>
              <Button
                variant="contained"
                size="small"
                startIcon={<ContentCopyOutlined />}
                onClick={copy}
                sx={{ textTransform: 'none' }}
              >
                {copied ? t('general.copied') : t('general.copy')}
              </Button>
              <Typography variant="caption" color="text.secondary">
                {t('library.embedHint')}
              </Typography>
            </Box>

            <Divider sx={{ my: 2 }} />
            <Typography variant="subtitle2">
              {t('library.embedPreview')}
            </Typography>
            <Box
              sx={{
                mt: 1,
                border: 1,
                borderColor: 'divider',
                borderRadius: 1,
                overflow: 'hidden',
                bgcolor: 'grey.100',
              }}
            >
              {/*
                The real page in a real iframe, at the URL being generated — so the preview cannot
                drift from what gets pasted, and a broken snippet looks broken here first.
                `key` forces a reload when the flags change: the src is a new URL, but the page
                reads its flags once at mount.
              */}
              <iframe
                key={`${src}#${previewNonce}`}
                src={src}
                title={t('library.embedPreview')}
                width="100%"
                // Driven by the embed's own resize message, so the preview never scrolls inside
                // itself — the dialog scrolls, and this is the last thing in it.
                height={previewHeight}
                scrolling="no"
                style={{ border: 'none', display: 'block', background: 'white' }}
              />
            </Box>

          </Box>
        )}
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
