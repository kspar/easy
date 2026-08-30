import { useEffect, useMemo, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import {
  Alert,
  Box,
  CircularProgress,
  CssBaseline,
  IconButton,
  Link,
  ThemeProvider,
  Typography,
} from '@mui/material'
import { DarkModeOutlined, LightModeOutlined, OpenInNewOutlined } from '@mui/icons-material'
import { Tooltip } from '@mui/material'
import { useTranslation } from 'react-i18next'
import config from '../../config.ts'
import { createAppTheme } from '../../theme/theme.ts'
import logoSvg from '../../assets/logo.svg'
import CodeEditor from '../../components/CodeEditor.tsx'
import RenderedMarkdown from '../../components/markdown/RenderedMarkdown.tsx'
import { RobotIcon } from '../../components/icons.tsx'
import AutoTestResults from '../course-exercise/AutoTestResults.tsx'
import { useAnonymousAutoassess, useAnonymousExercise } from '../../api/anonymousExercise.ts'
import useEmbedTheme from '../../hooks/useEmbedTheme.ts'

/**
 * An exercise rendered for embedding in someone else's page — the React half of EZ-1698.
 *
 * ## Why the URL looks the way it does
 *
 * The path and query scheme are wui's, unchanged: `/embed/exercises/:id/<title slug>` with
 * **valueless negative flags** — `no-title`, `no-border`, `no-template`, `no-dynamic-resize` — plus
 * a positive `submit`. Both the title slug and the query string are optional; production has live
 * embeds of the shape `/embed/exercises/342?…` with neither.
 *
 * Embeds published before September 2023 carry the *opt-in* scheme this replaced (`title`,
 * `border`, `template`, `dynamic-resize` — wui commit 9b995488, which added no back-compat).
 * Those parameter names are simply ignored now, and for `title` and `dynamic-resize` the new
 * defaults land on the same behaviour, which is why nobody noticed. `border` is the one that
 * genuinely changed meaning: an old embed that omitted `border` wanted no border and gets one
 * today. That drift happened in wui in 2023 and is reproduced here deliberately — matching
 * current production matters more than honouring an intent from two schemes ago.
 *
 * ## Not a normal page
 *
 * - **No authentication.** Both API calls go out with `noAuth`. `AuthProvider` skips Keycloak
 *   entirely on this path, so embedding an exercise cannot fire a hidden IdP iframe inside a
 *   third-party page.
 * - **Themed by the visitor, not the teacher.** It follows the reader's OS preference until they
 *   say otherwise, and their choice is remembered. Deliberately not the teacher's Lahendus theme:
 *   the person reading an embed on a wiki page is usually not a Lahendus user at all, and a
 *   teacher's dark mode should not put a dark rectangle on someone else's white page.
 * - **Reports its own height.** It cannot resize its iframe, so it measures itself and posts the
 *   height to the parent, where `/static/js/ez-embed-frame-resizer.js` applies it.
 */
export default function EmbedExercisePage() {
  const { t } = useTranslation()
  const { exerciseId } = useParams()
  const [search] = useSearchParams()

  const showTitle = !search.has('no-title')
  const showBorder = !search.has('no-border')
  const wantSubmit = search.has('submit')
  const showTemplate = !search.has('no-template')
  const dynamicResize = !search.has('no-dynamic-resize')
  const titleAlias = search.get('title-alias')
  const courseId = search.get('course')
  const courseExerciseId = search.get('exercise')

  const { data: exercise, isLoading, isError } = useAnonymousExercise(exerciseId)
  const assess = useAnonymousAutoassess(exerciseId)

  const [solution, setSolution] = useState('')
  const templateApplied = useRef(false)

  // The editor is seeded once, when the exercise arrives. Keying it to the data on every render
  // would wipe whatever the visitor has typed each time the query refetches.
  useEffect(() => {
    if (!exercise || templateApplied.current) return
    templateApplied.current = true
    if (showTemplate && exercise.anonymous_autoassess_template) {
      setSolution(exercise.anonymous_autoassess_template)
    }
  }, [exercise, showTemplate])

  const [mode, toggleMode] = useEmbedTheme()
  const theme = useMemo(() => createAppTheme(mode), [mode])

  /**
   * The environment marking (EZ-1733) reaches the footer too, and this is the one place where the
   * audience is not the person using Lahendus. The snippet the embed dialog generates is built
   * from `window.location.origin`, so one produced on dev carries dev URLs — and it keeps
   * working, because dev is internet-reachable. Nothing else would ever reveal that a course
   * page has been quietly showing a dev exercise for a term. wui did the same thing by calling
   * itself "DevLahendus" on dev.
   */
  const environment = config.environment

  // Submitting is offered only when the embed asked for it *and* the exercise can actually be
  // auto-assessed. A teacher-graded exercise has nowhere to send a solution.
  const canSubmit = wantSubmit && exercise?.submit_allowed === true
  const submitUnavailable = wantSubmit && exercise?.submit_allowed === false

  useFrameResize(dynamicResize)

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <Box
        sx={{
          p: showBorder ? 2 : 0,
          border: showBorder ? 1 : 0,
          borderColor: 'divider',
          borderRadius: showBorder ? 1 : 0,
          bgcolor: 'background.paper',
        }}
      >
        {isLoading && (
          <Box display="flex" justifyContent="center" py={4}>
            <CircularProgress size={28} />
          </Box>
        )}

        {isError && <Alert severity="error">{t('embed.loadFailed')}</Alert>}

        {exercise && (
          <>
            {showTitle && (
              <Typography variant="h6" gutterBottom>
                {titleAlias ?? exercise.title}
              </Typography>
            )}

            {exercise.text_html && (
              <RenderedMarkdown html={exercise.text_html} />
            )}

            {submitUnavailable && (
              <Alert severity="info" sx={{ mt: 2 }}>
                {t('embed.noAutograde')}
              </Alert>
            )}

            {canSubmit && (
              <Box mt={2.5}>
                {/*
                  The run button sits *inside* the editor, top right, which is where wui had it.
                  Two reasons, both still good: it costs no vertical space in an iframe whose height
                  someone has to live with, and a small robot reads as "try this" rather than
                  "hand it in" — nothing here is saved against a student, and a full-width Submit
                  button implies otherwise.
                */}
                <Box sx={{ position: 'relative' }}>
                  <CodeEditor
                    value={solution}
                    onChange={setSolution}
                    minHeight="10rem"
                    lineNumbers={false}
                    placeholder={t('embed.solutionPlaceholder')}
                    readOnly={assess.isPending}
                  />
                  <Tooltip title={t('embed.testTooltip')} placement="left">
                    <Box sx={{ position: 'absolute', top: 8, right: 8, zIndex: 2 }}>
                      <IconButton
                        aria-label={t('embed.submit')}
                        disabled={assess.isPending || solution.trim() === ''}
                        onClick={() => assess.mutate(solution)}
                        sx={{
                          bgcolor: 'primary.main',
                          color: 'primary.contrastText',
                          boxShadow: 2,
                          '&:hover': { bgcolor: 'primary.dark' },
                          '&.Mui-disabled': { bgcolor: 'action.disabledBackground', color: 'action.disabled' },
                        }}
                      >
                        {assess.isPending ? (
                          <CircularProgress size={22} color="inherit" />
                        ) : (
                          <RobotIcon />
                        )}
                      </IconButton>
                    </Box>
                  </Tooltip>
                </Box>

                {assess.isError && (
                  <Alert severity="error" sx={{ mt: 2 }}>
                    {t('embed.submitFailed')}
                  </Alert>
                )}

                {assess.data && (
                  <Box mt={2}>
                    <AutoTestResults autoAssessment={assess.data} />
                  </Box>
                )}
              </Box>
            )}

            {/*
              wui's label — "<title> · Lahendus" — with an external-link icon added, since this is
              the one thing on the page that leaves the host site. No underline: the icon already
              says "link", and an underline under a two-part label with a middle dot in it breaks
              the line up rather than tying it together.
            */}
            {courseId && courseExerciseId && (
              <Box mt={2}>
                <Link
                  href={`/courses/${courseId}/exercises/${courseExerciseId}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  variant="body2"
                  underline="none"
                  sx={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 0.6,
                    fontWeight: 500,
                    '&:hover': { color: 'primary.dark' },
                  }}
                >
                  {titleAlias ?? exercise.title}
                  <Box component="span" aria-hidden sx={{ opacity: 0.45 }}>
                    ·
                  </Box>
                  <Box component="span" sx={{ opacity: 0.75 }}>
                    Lahendus
                  </Box>
                  {environment && (
                    <>
                      <Box component="span" aria-hidden sx={{ opacity: 0.45 }}>
                        ·
                      </Box>
                      <Box component="span" sx={{ color: environment.colour, fontWeight: 700 }}>
                        {environment.label}
                      </Box>
                    </>
                  )}
                  <OpenInNewOutlined sx={{ fontSize: 15, opacity: 0.75 }} />
                </Link>
              </Box>
            )}

            {/*
              Brand mark rather than the word alone: this renders on someone else's page, where it
              is the only thing saying where the exercise came from. Same logo and green as the app
              header, so an embed looks like part of Lahendus and not a stray box.
            */}
            <Box
              sx={{
                display: 'flex',
                justifyContent: 'flex-end',
                alignItems: 'center',
                gap: 0.6,
                mt: 2.5,
              }}
            >
              <Box
                component="img"
                src={logoSvg}
                alt=""
                sx={{
                  width: 16,
                  height: 16,
                  // The svg has no fill of its own; the app header tints it the same way.
                  filter: 'invert(42%) sepia(52%) saturate(600%) hue-rotate(84deg) brightness(92%)',
                }}
              />
              {/*
                Wordmark and id read as one lockup rather than a label with a stray grey tag beside
                it: same face, same size, same green, the id just dimmed and separated by a middle
                dot. Hovering brings it to full strength so it still announces itself as a link.
              */}
              <Typography
                component="span"
                sx={{
                  fontFamily: "'Sniglet', cursive",
                  fontSize: '0.85rem',
                  color: 'primary.main',
                  letterSpacing: '0.02em',
                }}
              >
                LAHENDUS
              </Typography>
              {environment && (
                <>
                  <Typography
                    component="span"
                    aria-hidden
                    sx={{ fontSize: '0.85rem', color: 'primary.main', opacity: 0.45, lineHeight: 1 }}
                  >
                    ·
                  </Typography>
                  <Typography
                    component="span"
                    sx={{
                      fontFamily: "'Sniglet', cursive",
                      fontSize: '0.85rem',
                      letterSpacing: '0.02em',
                      color: environment.colour,
                    }}
                  >
                    {environment.label}
                  </Typography>
                </>
              )}
              <Typography
                component="span"
                aria-hidden
                sx={{ fontSize: '0.85rem', color: 'primary.main', opacity: 0.45, lineHeight: 1 }}
              >
                ·
              </Typography>
              <Link
                href={`/library/exercise/${exerciseId}`}
                target="_blank"
                rel="noopener noreferrer"
                sx={{
                  fontFamily: "'Sniglet', cursive",
                  fontSize: '0.85rem',
                  letterSpacing: '0.02em',
                  color: 'primary.main',
                  opacity: 0.65,
                  textDecoration: 'none',
                  '&:hover': { opacity: 1, textDecoration: 'underline' },
                }}
              >
                #{exerciseId}
              </Link>
              <Tooltip title={t(mode === 'dark' ? 'embed.themeLight' : 'embed.themeDark')}>
                <IconButton
                  aria-label={t(mode === 'dark' ? 'embed.themeLight' : 'embed.themeDark')}
                  onClick={toggleMode}
                  size="small"
                  sx={{ ml: 0.5, color: 'text.disabled', '&:hover': { color: 'text.secondary' } }}
                >
                  {mode === 'dark'
                    ? <LightModeOutlined sx={{ fontSize: 16 }} />
                    : <DarkModeOutlined sx={{ fontSize: 16 }} />}
                </IconButton>
              </Tooltip>
            </Box>
          </>
        )}
      </Box>
    </ThemeProvider>
  )
}

interface FrameResizeMessage {
  url: string
  height: number
  type: 'ez-frame-resize'
}

/**
 * Measure the document and tell the parent how tall to make the iframe.
 *
 * The message shape is wui's and has to stay that way: the listener reading it is the script
 * already embedded in published pages, matching on `type` and looking the iframe up by its exact
 * `src`. Sending anything else means every existing embed keeps whatever height it was born with.
 */
function useFrameResize(enabled: boolean) {
  useEffect(() => {
    if (!enabled || typeof ResizeObserver === 'undefined') return

    const post = (height: number) => {
      const message: FrameResizeMessage = {
        url: window.location.toString(),
        height,
        type: 'ez-frame-resize',
      }
      // Stringified, not structured: the existing parent script JSON.parses `m.data`.
      window.parent.postMessage(JSON.stringify(message), '*')
    }

    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const box = entry.borderBoxSize?.[0]
        // +1 for the same reason wui had it: a fractional height floors to a pixel short and the
        // content gets a scrollbar it does not need.
        if (box) post(Math.round(box.blockSize) + 1)
      }
    })
    observer.observe(document.body)
    return () => observer.disconnect()
  }, [enabled])
}
