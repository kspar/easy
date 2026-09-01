import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Box,
  Breadcrumbs,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Divider,
  IconButton,
  Link,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Paper,
  Snackbar,
  Tab,
  Tabs,
  Typography,
} from '@mui/material'
import {
  CodeOutlined,
  EditOutlined,
  MoreVertOutlined,
  PersonAddAltOutlined,
  PlaylistAddOutlined,
} from '@mui/icons-material'
import { useTranslation } from 'react-i18next'
import { useBlocker, useNavigate, useParams } from 'react-router-dom'
import {
  fetchLibraryExercise,
  useLibraryDirParents,
  useLibraryExercise,
  useUpdateLibraryExercise,
} from '../../api/library.ts'
import { useMarkdownPreview } from '../../api/exercises.ts'
import type { LibraryExerciseDetail, LibraryExerciseUpdate } from '../../api/types.ts'
import usePageTitle from '../../hooks/usePageTitle.ts'
import useRecentExercises from '../../hooks/useRecentExercises.ts'
import RelativeTime from '../../components/RelativeTime.tsx'
import RenderedMarkdown from '../../components/markdown/RenderedMarkdown.tsx'
import AddToCourseDialog from './AddToCourseDialog.tsx'
import ShareDialog from './ShareDialog.tsx'
import EmbedDialog from './EmbedDialog.tsx'
import ExerciseTextTab from './ExerciseTextTab.tsx'
import AutoAssessTab from './AutoAssessTab.tsx'
import {
  assetsForSave,
  autoAssessDraftFrom,
  isAutoAssessValid,
  isExerciseTextValid,
  mergeField,
  TSL_SPEC_FILENAME,
  type AutoAssessDraft,
} from './exerciseDraft.ts'
import TeacherTestingTab from '../course-exercise/TeacherTestingTab.tsx'
import { parseSpec, specTestProblems } from './tsl/tslModel.ts'
import { isTslContainer } from './autoEvalTypes.ts'
import { dirLink, hasAccess, spaLinkProps } from './links.ts'
import { errorMessage } from '../../api/errorMessage.ts'
import { record } from '../bug-report/breadcrumbs.ts'

type TabId = 'text' | 'autoassess' | 'testing'

interface Draft extends AutoAssessDraft {
  title: string
  textMd: string
}

function draftFrom(ex: LibraryExerciseDetail): Draft {
  return {
    title: ex.title,
    textMd: ex.text_md ?? '',
    ...autoAssessDraftFrom(ex),
  }
}


function toUpdate(d: Draft): LibraryExerciseUpdate {
  const hasAuto = d.containerImage !== null
  return {
    title: d.title,
    text_md: d.textMd.trim() === '' ? null : d.textMd,
    grader_type: hasAuto ? 'AUTO' : 'TEACHER',
    solution_file_name: d.solutionFileName,
    solution_file_type: d.solutionFileType,
    grading_script: hasAuto ? d.gradingScript : null,
    container_image: d.containerImage,
    max_time_sec: hasAuto ? d.maxTimeSec : null,
    max_mem_mb: hasAuto ? d.maxMemMb : null,
    assets: assetsForSave(d),
  }
}

export default function ExercisePage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { exerciseId } = useParams()
  const { data: exercise, isLoading, error, refetch } = useLibraryExercise(exerciseId)
  const { data: parents } = useLibraryDirParents(exercise?.dir_id)
  const { addRecent } = useRecentExercises()
  const updateExercise = useUpdateLibraryExercise(exerciseId)

  const [tab, setTab] = useState<TabId>('text')
  const [editing, setEditing] = useState(false)
  // Only set while editing — outside an edit session the draft is just the server copy, so
  // there is nothing to keep in sync and a background refetch can't clobber unsaved typing.
  const [editedDraft, setEditedDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [snackbar, setSnackbar] = useState<string | null>(null)
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)
  const [addToCourseOpen, setAddToCourseOpen] = useState(false)
  const [shareOpen, setShareOpen] = useState(false)
  const [embedOpen, setEmbedOpen] = useState(false)
  // Whether the TSL spec parses and compiles. Not derivable from the draft — only the compiler
  // knows — so the TSL editor reports it up. Non-TSL exercises leave it true.
  const [tslValid, setTslValid] = useState(true)

  // The version the current edit session started from — one side of the three-way merge.
  const baseRef = useRef<LibraryExerciseDetail | null>(null)

  usePageTitle(exercise?.title)

  useEffect(() => {
    if (exerciseId && exercise) addRecent(exerciseId, exercise.title)
  }, [exerciseId, exercise?.title]) // eslint-disable-line react-hooks/exhaustive-deps

  const serverDraft = useMemo(() => (exercise ? draftFrom(exercise) : null), [exercise])
  const draft = editedDraft ?? serverDraft
  const setDraft = setEditedDraft

  const dirty = useMemo(() => {
    if (!serverDraft || !editedDraft) return false
    return JSON.stringify(editedDraft) !== JSON.stringify(serverDraft)
  }, [serverDraft, editedDraft])

  // Browser-level guard; the in-app exits ask through the same dialog below.
  useEffect(() => {
    if (!dirty) return
    const handler = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])

  // In-app navigation guard (audit X-017): the breadcrumb, the sidebar and the kebab's course
  // links are all router navigations, and before this only Cancel and the tab close asked.
  const blocker = useBlocker(dirty)
  // True when the discard question came from the Cancel button rather than a blocked navigation.
  const [cancelAsking, setCancelAsking] = useState(false)
  const discardAsking = cancelAsking || blocker.state === 'blocked'

  const keepEditing = () => {
    setCancelAsking(false)
    if (blocker.state === 'blocked') blocker.reset()
  }

  const discardEdits = () => {
    setCancelAsking(false)
    setEditedDraft(null)
    setEditing(false)
    // The edit session that wrote this flag is over; a stale false would wedge the next one, and
    // a stale value would even carry to another exercise, since the route reuses this component.
    setTslValid(true)
    // Discarding can remove the Testimine tab from the strip (a TSL choice thrown away on a
    // teacher-graded exercise); leave its state with it or it snaps back on the next TSL choice.
    if (tab === 'testing' && exercise?.grader_type !== 'AUTO') setTab('autoassess')
    if (blocker.state === 'blocked') blocker.proceed()
  }

  const canWrite = exercise != null && hasAccess(exercise.effective_access, 'PRAW')
  const canManage = exercise != null && hasAccess(exercise.effective_access, 'PRAWM')

  // If editing switches the container away from auto-assessment while the Testimine tab is open,
  // the tab disappears from the strip; follow it out rather than leaving a blank pane. Derived
  // rather than corrected in an effect — there is no state to keep, only a fallback to render.
  const autoAssessableNow = editing
    ? (draft?.containerImage ?? null) !== null
    : exercise?.grader_type === 'AUTO'
  const shownTab: TabId = tab === 'testing' && !autoAssessableNow ? 'autoassess' : tab

  /**
   * Rendered text, but no Markdown source — everything authored before EZ-1731's migration is
   * still in this state. The editor can only show an empty box, because there is nothing to load
   * into it.
   */
  const legacyNoMarkdown =
    exercise != null && exercise.text_md == null && (exercise.text_html ?? '') !== ''

  // The parts of TSL validity derivable from the draft alone: the spec parses and no test has a
  // blank required field (audit X-027). Derived here rather than trusted from TslEditor's report,
  // because the editor only reports while mounted — an exercise edited entirely from the Text tab
  // would otherwise save a spec the gate exists to refuse.
  const tslDraftValid = useMemo(() => {
    if (!draft || !isTslContainer(draft.containerImage)) return true
    const text = draft.assets.find((a) => a.file_name === TSL_SPEC_FILENAME)?.file_content ?? ''
    const r = parseSpec(text)
    if (!r.spec) return false
    return specTestProblems(r.spec).blankRequired === 0
  }, [draft])

  const isValid =
    draft != null &&
    isExerciseTextValid(draft.title) &&
    isAutoAssessValid(draft) &&
    tslDraftValid &&
    // Compile validity only TslEditor knows, and only while the container is actually TSL: the
    // flag is written only by TslEditor, so switching the auto-assessment away from TSL unmounts
    // its writer and a stale `false` would otherwise keep Save disabled on an exercise that no
    // longer has a TSL spec at all (audit X-022).
    (isTslContainer(draft.containerImage) ? tslValid : true) &&
    // A save writes text_html from text_md, so saving one of these with the box still empty
    // deletes the exercise text — and renaming the exercise was enough to do it, since nothing
    // else here depends on the text being touched. Refuse instead.
    !(legacyNoMarkdown && draft.textMd.trim() === '')

  // Live preview while editing; the server-rendered HTML otherwise. The preview is debounced, so
  // until the first one lands the saved HTML stands in — otherwise the pane blanks for a beat
  // every time you click Edit. Genuinely empty text still renders as empty.
  const previewHtml = useMarkdownPreview(editing ? (draft?.textMd ?? '') : '')
  const savedHtml = exercise?.text_html ?? ''
  const shownHtml = editing
    ? previewHtml || (draft?.textMd.trim() ? savedHtml : '')
    : savedHtml
  const shownTitle = editing ? (draft?.title ?? '') : (exercise?.title ?? '')

  async function startEditing() {
    if (!exerciseId || !exercise) return
    // Someone else may have saved since this page loaded — reload rather than let the user edit
    // a stale copy and only find out at save time.
    const current = await fetchLibraryExercise(exerciseId)
    if (JSON.stringify(draftFrom(current)) !== JSON.stringify(draftFrom(exercise))) {
      record('action', `exercise ${exerciseId} changed elsewhere; reloaded instead of editing`)
      setSnackbar(t('library.exerciseChangedElsewhere'))
      await refetch()
      return
    }
    record('action', `started editing exercise ${exerciseId}`)
    baseRef.current = current
    setDraft(draftFrom(current))
    setTslValid(true)
    setEditing(true)
  }

  function cancelEditing() {
    if (dirty) {
      setCancelAsking(true)
      return
    }
    setEditedDraft(null)
    setEditing(false)
    setTslValid(true)
  }

  async function save() {
    if (!exerciseId || !draft || !baseRef.current) return
    setSaving(true)
    try {
      const base = draftFrom(baseRef.current)
      const remoteDraft = draftFrom(await fetchLibraryExercise(exerciseId))

      const merged = { ...draft } as Record<string, unknown>
      // Named, not counted. Two teachers editing the same exercise is the situation this merge
      // exists for, and "the title reverted" versus "the grading script reverted" are different
      // bugs with different causes — a report that says only "there was a conflict" cannot tell
      // them apart, and the reporter cannot either, because the prompt does not say which fields.
      const conflicted: string[] = []
      for (const key of Object.keys(draft) as (keyof Draft)[]) {
        const [value, isConflict] = mergeField(draft[key], remoteDraft[key], base[key])
        merged[key] = value
        if (isConflict) conflicted.push(key)
      }
      const conflict = conflicted.length > 0

      if (conflict) {
        record('action', `save conflict on exercise ${exerciseId}, fields: ${conflicted.join(', ')}`)
        if (!window.confirm(t('library.mergeConflictConfirm'))) {
          record('action', `declined to overwrite; save of exercise ${exerciseId} abandoned`)
          setSaving(false)
          return
        }
        record('action', `chose to overwrite the conflicting fields on exercise ${exerciseId}`)
      }

      await updateExercise.mutateAsync(toUpdate(merged as unknown as Draft))
      setEditing(false)
      setEditedDraft(null)
      setTslValid(true)
      // Saving with the container cleared removes the Testimine tab; see discardEdits.
      if (tab === 'testing' && (merged as unknown as Draft).containerImage === null) setTab('autoassess')
      setSnackbar(t('library.exerciseSaved'))
      const fresh = await refetch()
      if (fresh.data) baseRef.current = fresh.data
    } catch (err) {
      // The request's own failure is already an `api` line; this says what the reporter was told
      // about it, which is the sentence they will quote back in the report.
      record('action', `saving exercise ${exerciseId} failed: ${errorMessage(err, t)}`)
      setSnackbar(errorMessage(err, t))
    } finally {
      setSaving(false)
    }
  }

  if (isLoading) {
    return (
      <Box display="flex" justifyContent="center" py={8}>
        <CircularProgress />
      </Box>
    )
  }

  if (error || !exercise || !draft || !exerciseId) {
    return <Alert severity="error">{t('general.noPermission')}</Alert>
  }

  // While editing, `autoAssessableNow` (above) follows the *edited* grader type (audit X-016):
  // a teacher who has just chosen TSL should not have to save and go hunting for a tab that
  // appeared. What the runs execute is still the saved version — the alerts inside the tab say
  // which, and until a version with auto-assessment has been saved there is nothing to run.
  const savedAuto = exercise.grader_type === 'AUTO'

  return (
    <>
      <Breadcrumbs sx={{ mb: 2 }}>
        <Typography
          component="a"
          {...spaLinkProps('/library/dir/root', navigate)}
          sx={{ textDecoration: 'none', color: 'text.secondary', '&:hover': { textDecoration: 'underline' } }}
        >
          {t('library.title')}
        </Typography>
        {parents?.map((p) => (
          <Typography
            key={p.id}
            component="a"
            {...spaLinkProps(dirLink(p.id, p.name), navigate)}
            sx={{ textDecoration: 'none', color: 'text.secondary', '&:hover': { textDecoration: 'underline' } }}
          >
            {p.name}
          </Typography>
        ))}
        <Typography color="text.primary" fontWeight={500}>
          {exercise.title}
        </Typography>
      </Breadcrumbs>

      <Box display="flex" alignItems="center" justifyContent="flex-end" gap={1} mb={2}>
        {canWrite &&
          (editing ? (
            <>
              <Button onClick={cancelEditing} disabled={saving}>
                {t('general.cancel')}
              </Button>
              <Button variant="contained" onClick={save} disabled={saving || !isValid}>
                {saving ? t('general.saving') : t('general.save')}
              </Button>
            </>
          ) : (
            <Button variant="outlined" startIcon={<EditOutlined />} onClick={startEditing}>
              {t('general.edit')}
            </Button>
          ))}
        <IconButton onClick={(e) => setMenuAnchor(e.currentTarget)} aria-label={t('general.moreOptions')}>
          <MoreVertOutlined />
        </IconButton>
      </Box>

      <Menu anchorEl={menuAnchor} open={!!menuAnchor} onClose={() => setMenuAnchor(null)}>
        <MenuItem
          onClick={() => {
            setMenuAnchor(null)
            setAddToCourseOpen(true)
          }}
        >
          <ListItemIcon>
            <PlaylistAddOutlined fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('library.addToCourse')}</ListItemText>
        </MenuItem>
        <MenuItem
          onClick={() => {
            setMenuAnchor(null)
            setEmbedOpen(true)
          }}
        >
          <ListItemIcon>
            <CodeOutlined fontSize="small" />
          </ListItemIcon>
          <ListItemText>{t('library.embedding')}</ListItemText>
        </MenuItem>
        {canManage && (
          <MenuItem
            onClick={() => {
              setMenuAnchor(null)
              setShareOpen(true)
            }}
          >
            <ListItemIcon>
              <PersonAddAltOutlined fontSize="small" />
            </ListItemIcon>
            <ListItemText>{t('library.share')}</ListItemText>
          </MenuItem>
        )}
      </Menu>

      {/* Preview left, editor right; stacks on anything narrower than a wide desktop. */}
      <Box
        display="grid"
        gridTemplateColumns={{ xs: '1fr', lg: 'minmax(0, 1fr) minmax(0, 1fr)' }}
        gap={3}
        alignItems="start"
      >
        <Paper variant="outlined" sx={{ p: 3, minWidth: 0 }}>
          <ExerciseAttributes exercise={exercise} />
          <Divider sx={{ my: 2 }} />
          <Typography variant="h5" gutterBottom>
            {shownTitle}
          </Typography>
          <RenderedMarkdown html={shownHtml} />
        </Paper>

        <Box minWidth={0}>
          <Tabs
            value={shownTab}
            onChange={(_, v) => setTab(v)}
            sx={{ mb: 2, '& .MuiTab-root': { textTransform: 'none' } }}
          >
            <Tab value="text" label={t('library.tabExercise')} />
            <Tab value="autoassess" label={t('library.tabAutoAssess')} />
            {autoAssessableNow && <Tab value="testing" label={t('library.tabTesting')} />}
          </Tabs>

          {shownTab === 'text' && (
            <ExerciseTextTab
              title={draft.title}
              textMd={draft.textMd}
              editing={editing}
              legacyNoMarkdown={legacyNoMarkdown}
              onTitleChange={(title) => setDraft({ ...draft, title })}
              onTextChange={(textMd) => setDraft({ ...draft, textMd })}
            />
          )}

          {shownTab === 'autoassess' && (
            <AutoAssessTab
              draft={draft}
              editing={editing}
              onChange={(next) => setDraft({ ...draft, ...next })}
              onTslValidChange={setTslValid}
            />
          )}

          {shownTab === 'testing' &&
            (savedAuto ? (
              <>
                {editing && (
                  <Alert severity="warning" sx={{ mb: 2 }}>
                    {t('library.testingWhileEditing')}
                  </Alert>
                )}
                <TeacherTestingTab
                  exerciseId={exerciseId}
                  solutionFileName={exercise.solution_file_name}
                  graderType="AUTO"
                />
              </>
            ) : (
              // Reachable only while editing (audit X-016): the tab exists because the *draft*
              // has auto-assessment, but runs execute the saved version, which has none yet.
              <Alert severity="warning" sx={{ mb: 2 }}>
                {t('library.testingNeedsSave')}
              </Alert>
            ))}
        </Box>
      </Box>

      <AddToCourseDialog
        exercises={[{ id: exerciseId, title: exercise.title }]}
        open={addToCourseOpen}
        onClose={() => setAddToCourseOpen(false)}
        onSuccess={(msg) => {
          setAddToCourseOpen(false)
          setSnackbar(msg)
          refetch()
        }}
      />

      <ShareDialog
        dirId={exercise.dir_id}
        itemName={exercise.title}
        isDir={false}
        open={shareOpen}
        onClose={() => setShareOpen(false)}
      />

      <EmbedDialog
        // Keyed by exercise: the dialog stays mounted when closed, so its exercise-specific state
        // — the title override, the course link — would otherwise survive a move to a different
        // exercise if the router reuses this page instance. Snippet options are meant to carry
        // over and do so through localStorage instead.
        key={exerciseId}
        exerciseId={exerciseId}
        open={embedOpen}
        onClose={() => setEmbedOpen(false)}
      />

      <Snackbar
        open={!!snackbar}
        autoHideDuration={4000}
        onClose={() => setSnackbar(null)}
        message={snackbar}
      />

      {/* One dialog for every exit from a dirty edit session: Cancel and blocked navigations. */}
      <Dialog open={discardAsking} onClose={keepEditing}>
        <DialogTitle>{t('library.unsavedChangesTitle')}</DialogTitle>
        <DialogContent>
          <DialogContentText>{t('library.unsavedChangesConfirm')}</DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={keepEditing}>{t('library.keepEditing')}</Button>
          <Button color="error" onClick={discardEdits}>
            {t('library.discard')}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}

function ExerciseAttributes({ exercise }: { exercise: LibraryExerciseDetail }) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [showAll, setShowAll] = useState(false)

  const courses = exercise.on_courses
  const shown = showAll ? courses : courses.slice(0, 5)
  const hiddenCount = exercise.on_courses_no_access
  const totalCount = courses.length + hiddenCount

  return (
    <Box display="flex" flexDirection="column" gap={0.5}>
      <Typography variant="body2" color="text.secondary">
        {t('library.modifiedAt')}: <RelativeTime date={exercise.last_modified} />
        {` · ${exercise.last_modified_by_id}`}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {t('library.usedOnCourses')}
        {totalCount > 0 ? ` (${totalCount})` : ': –'}
      </Typography>
      {shown.length > 0 && (
        <Box component="ul" sx={{ m: 0, pl: 3 }}>
          {shown.map((c) => (
            <Box component="li" key={c.course_exercise_id}>
              <Link
                {...spaLinkProps(`/courses/${c.id}/exercises/${c.course_exercise_id}`, navigate)}
                underline="hover"
                variant="body2"
              >
                {(c.alias ?? c.title) +
                  (c.course_exercise_title_alias ? ` (${c.course_exercise_title_alias})` : '')}
              </Link>
            </Box>
          ))}
        </Box>
      )}
      {!showAll && courses.length > 5 && (
        <Button size="small" sx={{ alignSelf: 'flex-start' }} onClick={() => setShowAll(true)}>
          {t('library.showAllCourses', { count: courses.length - 5 })}
        </Button>
      )}
      {hiddenCount > 0 && (
        <Typography variant="body2" color="text.secondary">
          {t('library.hiddenCourses', { count: hiddenCount })}
        </Typography>
      )}
    </Box>
  )
}
