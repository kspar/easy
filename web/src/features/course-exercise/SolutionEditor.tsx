import { useCallback, useEffect, useImperativeHandle, useRef, useState, forwardRef } from 'react'
import { Alert, Box, Button, CircularProgress, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, IconButton, ListItemIcon, ListItemText, Menu, MenuItem, Snackbar, Tooltip, Typography } from '@mui/material'
import { SendOutlined, FileUploadOutlined, FileDownloadOutlined, MoreVertOutlined } from '@mui/icons-material'
import { useBlocker } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { EditorView, placeholder as cmPlaceholder } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { languageFromFilename } from './editorLanguage.ts'
import { oneDark } from '@codemirror/theme-one-dark'
import { basicSetup } from 'codemirror'
import { useTheme } from '@mui/material/styles'
import { useQueryClient } from '@tanstack/react-query'
import {
  useSubmitSolution,
  useAwaitAutograde,
  useSaveDraft,
  saveDraftKeepalive,
  draftQueryKey,
} from '../../api/exercises.ts'
import type { ExerciseDetails } from '../../api/types.ts'

export interface SolutionEditorHandle {
  setSolution: (solution: string) => void
}

export default forwardRef<SolutionEditorHandle, {
  courseId: string
  courseExerciseId: string
  exercise: ExerciseDetails
  initialSolution?: string
  /** True when initialSolution came from a saved draft rather than a submission. */
  initialIsDraft?: boolean
  /**
   * False when the draft read failed, so the server may hold a draft this session never saw.
   * Autosave then stays off — writing would overwrite it — and leaving with typed work asks
   * instead of silently flushing.
   */
  autosaveEnabled?: boolean
  onSubmitted?: () => void
  onAutogradeStart?: () => void
}>(function SolutionEditor({
  courseId,
  courseExerciseId,
  exercise,
  initialSolution,
  initialIsDraft = false,
  autosaveEnabled = true,
  onSubmitted,
  onAutogradeStart,
}, ref) {
  const { t } = useTranslation()
  const theme = useTheme()
  const queryClient = useQueryClient()
  const editorRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)
  const prevExerciseRef = useRef(courseExerciseId)
  const [snackMsg, setSnackMsg] = useState<string | null>(null)
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)

  const submit = useSubmitSolution(courseId, courseExerciseId)
  const awaitAutograde = useAwaitAutograde(courseId, courseExerciseId)
  const isSubmitting = submit.isPending || awaitAutograde.isPending

  // --- Draft autosave (EZ-1758, audit X-001) ---
  //
  // Whatever is in the editor is worth keeping the moment it differs from what the server has.
  // Three escape routes, ordered by how much time they leave for the request:
  //  - a typing pause: debounced POST to the draft endpoint
  //  - in-app navigation: a router blocker flushes the save and lets the navigation continue;
  //    only a *failed* save asks the user anything
  //  - tab close: `visibilitychange`→hidden fires a keepalive save, and `beforeunload` raises
  //    the browser prompt only while an unsaved delta still exists
  const saveDraft = useSaveDraft(courseId, courseExerciseId)
  const saveDraftMutate = saveDraft.mutate
  const [draftState, setDraftState] = useState<'none' | 'restored' | 'saved' | 'error'>(
    initialIsDraft ? 'restored' : 'none',
  )
  const [leaveDialogOpen, setLeaveDialogOpen] = useState(false)
  // The doc content the server is known to hold, as a draft or a submission. `dirtyRef` stays
  // true from the first unsaved keystroke through an in-flight or failed save — it only clears
  // when the server is confirmed to have the current content.
  const lastSavedRef = useRef<string | null>(null)
  const dirtyRef = useRef(false)
  const saveTimerRef = useRef<number | null>(null)
  // Latched per exercise rather than read live: the editor seeds once, so a draft the query only
  // produced *later* — a focus refetch succeeding after the initial read failed — was never shown,
  // and flipping autosave back on mid-session would overwrite it. The latch resets with the
  // exercise-change effect below.
  const autosaveEnabledRef = useRef(autosaveEnabled)
  // Saves are serialized through this chain. Concurrent writes to the single upsert row would be
  // last-writer-wins on the *server's* arrival order, which can leave older text standing while
  // the client believes newer text was saved.
  const saveChainRef = useRef<Promise<void>>(Promise.resolve())

  const currentDoc = useCallback(() => viewRef.current?.state.doc.toString() ?? '', [])

  const cancelSaveTimer = useCallback(() => {
    if (saveTimerRef.current !== null) {
      clearTimeout(saveTimerRef.current)
      saveTimerRef.current = null
    }
  }, [])

  const markSaved = useCallback((solution: string) => {
    lastSavedRef.current = solution
    dirtyRef.current = currentDoc() !== solution
    if (!dirtyRef.current) setDraftState('saved')
  }, [currentDoc])

  // The write-through lives here, with the ids captured at call time, rather than in the hook —
  // an in-flight mutation adopts the hook's latest options, so a save resolving just after the
  // exercise id changed would otherwise land under the wrong cache key.
  const writeDraftCache = useCallback((solution: string) => {
    queryClient.setQueryData(
      draftQueryKey(courseId, courseExerciseId),
      { solution, created_at: new Date().toISOString() },
    )
  }, [queryClient, courseId, courseExerciseId])

  /**
   * Save the current doc, behind any save already in flight. Resolves once the server holds the
   * content that was current when the turn came; rejects when it could not be saved — including
   * when autosave is off (the draft endpoint never answered, so writing could overwrite a draft
   * the student was never shown).
   */
  const flushDraftSave = useCallback((): Promise<void> => {
    cancelSaveTimer()
    const attempt = saveChainRef.current.then(() => {
      if (viewRef.current === null) return
      const solution = currentDoc()
      if (solution === lastSavedRef.current) return
      if (!autosaveEnabledRef.current) {
        setDraftState('error')
        throw new Error('draft endpoint unavailable')
      }
      return new Promise<void>((resolve, reject) => {
        saveDraftMutate(solution, {
          onSuccess: () => {
            markSaved(solution)
            writeDraftCache(solution)
            resolve()
          },
          onError: () => {
            setDraftState('error')
            reject(new Error('draft save failed'))
          },
        })
      })
    })
    // The chain survives a failed link, so the next save still runs.
    saveChainRef.current = attempt.catch(() => {})
    return attempt
  }, [cancelSaveTimer, currentDoc, markSaved, writeDraftCache, saveDraftMutate])

  const scheduleDraftSave = useCallback(() => {
    // This runs on every keystroke, so the exact compare — O(doc) allocation included — is only
    // paid when the lengths tie; almost every edit is decided by the length alone.
    const doc = viewRef.current?.state.doc
    const saved = lastSavedRef.current
    dirtyRef.current =
      doc != null && (saved === null || doc.length !== saved.length || doc.toString() !== saved)
    if (!dirtyRef.current) {
      // An armed timer for a delta that no longer exists must not fire: after unmount it would
      // read the destroyed editor as '' and wipe the server draft with it.
      cancelSaveTimer()
      return
    }
    if (!autosaveEnabledRef.current) return
    if (saveTimerRef.current !== null) clearTimeout(saveTimerRef.current)
    saveTimerRef.current = window.setTimeout(() => {
      saveTimerRef.current = null
      flushDraftSave().catch(() => {
        // Already reflected in draftState; the blocker retries on the way out.
      })
    }, 2000)
  }, [cancelSaveTimer, flushDraftSave])

  // No timer may outlive the editor it reads from.
  useEffect(() => cancelSaveTimer, [cancelSaveTimer])

  // The CodeMirror update listener lives inside an effect with a frozen dependency list, so it
  // reaches the current callback through a ref rather than a closure.
  const scheduleDraftSaveRef = useRef(scheduleDraftSave)
  useEffect(() => {
    scheduleDraftSaveRef.current = scheduleDraftSave
  }, [scheduleDraftSave])

  // A new exercise means a new draft lifecycle; any pending save belonged to the old one and was
  // flushed by the blocker on the way here.
  useEffect(() => {
    if (saveTimerRef.current !== null) {
      clearTimeout(saveTimerRef.current)
      saveTimerRef.current = null
    }
    dirtyRef.current = false
    autosaveEnabledRef.current = autosaveEnabled
    setDraftState(initialIsDraft ? 'restored' : 'none')
    // Keyed on the exercise alone — initialIsDraft and autosaveEnabled are snapshots taken here,
    // per the latch comment above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [courseExerciseId])

  const blocker = useBlocker(
    useCallback(
      ({ currentLocation, nextLocation }: {
        currentLocation: { pathname: string }
        nextLocation: { pathname: string }
      }) => dirtyRef.current && currentLocation.pathname !== nextLocation.pathname,
      [],
    ),
  )

  useEffect(() => {
    if (blocker.state !== 'blocked') return
    flushDraftSave().then(
      () => blocker.proceed(),
      () => setLeaveDialogOpen(true),
    )
    // Deliberately keyed on the state alone: the promise above resolves this particular block,
    // and re-running on unrelated renders would fire the save twice.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [blocker.state])

  useEffect(() => {
    const beforeUnload = (e: BeforeUnloadEvent) => {
      if (dirtyRef.current) e.preventDefault()
    }
    const visibilityChange = () => {
      if (document.visibilityState !== 'hidden' || !dirtyRef.current || !autosaveEnabledRef.current) return
      // Same guard as flushDraftSave: during a theme toggle the editor is destroyed and re-created
      // asynchronously, and reading the gap as '' would wipe the server draft with it.
      if (viewRef.current === null) return
      // The debounce timer must not double-post behind the keepalive save.
      cancelSaveTimer()
      const solution = currentDoc()
      // Browsers cap keepalive bodies at ~64 KiB of *serialized bytes* — multi-byte characters and
      // JSON escapes inflate past the character count, so measure the actual payload. Oversized
      // content takes the normal path: it still saves on an ordinary tab switch, and an actual
      // close is guarded by the beforeunload prompt above.
      if (new Blob([JSON.stringify({ solution })]).size > 60_000) {
        flushDraftSave().catch(() => {})
        return
      }
      // Serialized behind any in-flight save, like every other write to the one draft row.
      const attempt = saveChainRef.current.then(() => {
        if (currentDoc() !== solution || solution === lastSavedRef.current) return
        return saveDraftKeepalive(courseId, courseExerciseId, solution).then(() => {
          markSaved(solution)
          writeDraftCache(solution)
        })
      })
      saveChainRef.current = attempt.catch(() => {})
      attempt.catch(() => {
        // The page may already be gone; if not, retry through the normal transport once.
        flushDraftSave().catch(() => {})
      })
    }
    window.addEventListener('beforeunload', beforeUnload)
    document.addEventListener('visibilitychange', visibilityChange)
    return () => {
      window.removeEventListener('beforeunload', beforeUnload)
      document.removeEventListener('visibilitychange', visibilityChange)
    }
  }, [courseId, courseExerciseId, currentDoc, markSaved, writeDraftCache, cancelSaveTimer, flushDraftSave])

  // Initialize CodeMirror (re-creates on theme or exercise change)
  useEffect(() => {
    if (!editorRef.current) return
    let cancelled = false

    const exerciseChanged = prevExerciseRef.current !== courseExerciseId
    prevExerciseRef.current = courseExerciseId

    // Preserve user edits on theme change; reset on exercise change
    const prevDoc = exerciseChanged ? undefined : viewRef.current?.state.doc.toString()
    viewRef.current?.destroy()
    viewRef.current = null

    languageFromFilename(exercise.solution_file_name).then((lang) => {
      if (cancelled || !editorRef.current) return

      const extensions = [
        basicSetup,
        lang,
        cmPlaceholder(t('submission.editorPlaceholder')),
        EditorView.lineWrapping,
        EditorView.theme({ '.cm-content': { paddingTop: '4px' } }),
        // The student's primary input had no accessible name at all — a gate-level
        // `aria-input-field-name` violation on the app's most-used surface (audit X-002). This
        // editor builds its own view rather than using CodeEditor, so it needs its own.
        EditorView.contentAttributes.of({ 'aria-label': t('submission.solutionEditorLabel') }),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) scheduleDraftSaveRef.current()
        }),
      ]
      if (theme.palette.mode === 'dark') {
        extensions.push(oneDark)
      }

      // `??`, not `||`: an empty prevDoc is a real state — the student deleted everything — and
      // falling back to initialSolution would resurrect the deleted content on a theme toggle.
      const state = EditorState.create({
        doc: prevDoc ?? initialSolution ?? '',
        extensions,
      })

      // Content seeded from the server (a draft or a submission) is by definition already saved.
      // A theme change that preserved the user's edits must not launder them into "saved".
      if (exerciseChanged || prevDoc === undefined) {
        lastSavedRef.current = state.doc.toString()
        dirtyRef.current = false
      }

      viewRef.current = new EditorView({
        state,
        parent: editorRef.current,
      })
    })

    return () => {
      cancelled = true
      viewRef.current?.destroy()
      viewRef.current = null
    }
    // initialSolution is deliberately not a dependency: the page renders this component only
    // after the draft and submissions have loaded, so its value is settled per exercise — and a
    // later query refetch (the draft cache is written through on every autosave) must not
    // destroy and re-create the editor under the user's cursor.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme.palette.mode, courseExerciseId, exercise.solution_file_name])

  useImperativeHandle(ref, () => ({
    setSolution: (solution: string) => {
      const view = viewRef.current
      if (view) {
        view.dispatch({
          changes: { from: 0, to: view.state.doc.length, insert: solution },
        })
      }
    },
  }))

  const getSolution = useCallback(() => {
    return viewRef.current?.state.doc.toString() ?? ''
  }, [])

  const handleDownload = useCallback(() => {
    const solution = getSolution()
    const blob = new Blob([solution], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${courseExerciseId}_${Date.now()}_${exercise.solution_file_name}`
    a.click()
    URL.revokeObjectURL(url)
  }, [getSolution, courseExerciseId, exercise.solution_file_name])

  const handleUpload = useCallback(() => {
    const input = document.createElement('input')
    input.type = 'file'
    input.onchange = () => {
      const file = input.files?.[0]
      if (!file) return
      if (file.size > 300_000) {
        setSnackMsg(t('submission.uploadErrorTooLarge'))
        return
      }
      const reader = new FileReader()
      reader.onload = () => {
        try {
          const text = new TextDecoder('utf-8', { fatal: true }).decode(reader.result as ArrayBuffer)
          const view = viewRef.current
          if (view) {
            view.dispatch({
              changes: { from: 0, to: view.state.doc.length, insert: text },
            })
          }
        } catch {
          setSnackMsg(t('submission.uploadErrorNotText'))
        }
      }
      reader.readAsArrayBuffer(file)
    }
    input.click()
  }, [t])

  const refetchAfterSubmit = useCallback(() => {
    queryClient.refetchQueries({
      queryKey: ['student', 'courses', courseId, 'exercises', courseExerciseId, 'submissions'],
    })
    queryClient.refetchQueries({
      queryKey: ['student', 'courses', courseId, 'exercises'],
    })
  }, [queryClient, courseId, courseExerciseId])

  const handleSubmit = useCallback(() => {
    const solution = getSolution()
    if (!solution.trim()) return

    submit.mutate(solution, {
      onSuccess: () => {
        // The submission now holds this content, so the draft machinery has nothing to protect.
        cancelSaveTimer()
        lastSavedRef.current = solution
        dirtyRef.current = currentDoc() !== solution
        setDraftState('none')
        if (dirtyRef.current) {
          // Typed during the submit round-trip; its timer was just cancelled, and the only other
          // arming site is the next keystroke. The scheduled save also supersedes the sync below.
          scheduleDraftSave()
        } else if (autosaveEnabledRef.current) {
          // Bring the server draft up to the submission too. Otherwise the draft row keeps the
          // last pre-submit autosave — older *and* different — and whether a later visit restores
          // it correctly would hang on a timestamp comparison. Serialized behind the chain like
          // every other draft write; dispatched bare it could race an in-flight autosave and
          // leave older text standing as last writer.
          const attempt = saveChainRef.current.then(
            () =>
              new Promise<void>((resolve) => {
                saveDraftMutate(solution, {
                  onSuccess: () => {
                    writeDraftCache(solution)
                    resolve()
                  },
                  // Best-effort: the submission itself succeeded, and the stale draft row is
                  // older than the submission, so a later visit still restores correctly.
                  onError: () => resolve(),
                })
              }),
          )
          saveChainRef.current = attempt
        }
        setSnackMsg(t('submission.submitSuccess'))
        refetchAfterSubmit()
        if (exercise.grader_type === 'AUTO') {
          onAutogradeStart?.()
          awaitAutograde.mutate()
        } else {
          onSubmitted?.()
        }
      },
    })
  }, [getSolution, submit, awaitAutograde, exercise.grader_type, t, onSubmitted, onAutogradeStart, refetchAfterSubmit, currentDoc, cancelSaveTimer, saveDraftMutate, writeDraftCache, scheduleDraftSave])

  // When autograde completes: refetch submissions (for results data) but NOT the
  // exercises list — the parent delays that until the reveal animation finishes.
  useEffect(() => {
    if (awaitAutograde.isSuccess) {
      queryClient.refetchQueries({
        queryKey: ['student', 'courses', courseId, 'exercises', courseExerciseId, 'submissions'],
      })
      onSubmitted?.()
    }
  }, [awaitAutograde.isSuccess, onSubmitted, queryClient, courseId, courseExerciseId])

  return (
    <Box>
      <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, overflow: 'hidden', mb: 2 }}>
        <Box sx={{
          display: 'flex',
          alignItems: 'center',
          px: 1.5,
          py: 0.5,
          borderBottom: 1,
          borderColor: 'divider',
          bgcolor: theme.palette.mode === 'dark' ? '#282c34' : '#f5f5f5',
        }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
            {exercise.solution_file_name}
          </Typography>
          <Box sx={{ flex: 1 }} />
          {draftState !== 'none' && (
            <Typography
              variant="caption"
              color={draftState === 'error' ? 'warning.main' : 'text.secondary'}
              sx={{ mr: 1 }}
            >
              {draftState === 'restored'
                ? t('submission.draftRestored')
                : draftState === 'saved'
                  ? t('submission.draftSaved')
                  : t('submission.draftSaveFailed')}
            </Typography>
          )}
          <Tooltip title={t('general.moreOptions')}>
            <IconButton size="small" onClick={e => setMenuAnchor(e.currentTarget)}>
              <MoreVertOutlined fontSize="small" />
            </IconButton>
          </Tooltip>
          <Menu anchorEl={menuAnchor} open={Boolean(menuAnchor)} onClose={() => setMenuAnchor(null)}>
            {exercise.is_open && (
              <MenuItem onClick={() => { setMenuAnchor(null); handleUpload() }}>
                <ListItemIcon><FileUploadOutlined fontSize="small" /></ListItemIcon>
                <ListItemText>{t('submission.uploadFile')}</ListItemText>
              </MenuItem>
            )}
            <MenuItem onClick={() => { setMenuAnchor(null); handleDownload() }}>
              <ListItemIcon><FileDownloadOutlined fontSize="small" /></ListItemIcon>
              <ListItemText>{t('submission.saveAsFile')}</ListItemText>
            </MenuItem>
          </Menu>
        </Box>
        <Box
          ref={editorRef}
          sx={{
            '& .cm-editor': { minHeight: 200, cursor: 'text' },
            '& .cm-focused': { outline: 'none' },
            '& .cm-scroller': { cursor: 'text' },
          }}
        />
      </Box>

      {exercise.is_open && (
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
          <Button
            variant="contained"
            startIcon={
              isSubmitting ? <CircularProgress size={18} /> : <SendOutlined />
            }
            onClick={handleSubmit}
            disabled={isSubmitting}
          >
            {exercise.grader_type === 'AUTO'
              ? t('submission.submitAndCheck')
              : t('submission.submit')}
          </Button>
        </Box>
      )}

      {submit.isError && (
        <Alert severity="error" sx={{ mt: 2 }}>
          {t('general.somethingWentWrong')}
        </Alert>
      )}

      <Snackbar
        open={snackMsg !== null}
        autoHideDuration={3000}
        onClose={() => setSnackMsg(null)}
        message={snackMsg}
      />

      {/* Only reachable when a navigation was blocked and the flush save failed. */}
      <Dialog
        open={leaveDialogOpen}
        onClose={() => {
          setLeaveDialogOpen(false)
          blocker.reset?.()
        }}
      >
        <DialogTitle>{t('submission.draftSaveFailed')}</DialogTitle>
        <DialogContent>
          <DialogContentText>{t('submission.leaveUnsavedBody')}</DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button
            onClick={() => {
              setLeaveDialogOpen(false)
              blocker.reset?.()
            }}
          >
            {t('submission.stay')}
          </Button>
          <Button
            color="error"
            onClick={() => {
              setLeaveDialogOpen(false)
              blocker.proceed?.()
            }}
          >
            {t('submission.leaveWithoutSaving')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
})
