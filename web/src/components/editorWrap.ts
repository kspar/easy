/**
 * Soft wrap, as a setting rather than a decision the app makes for everyone.
 *
 * Every CodeMirror in this application had `EditorView.lineWrapping` hard-coded, which is right for
 * prose and wrong for code: a wrapped line hides where the line actually ends, so indentation stops
 * lining up and a column number stops meaning anything. It is worst on exactly the content this
 * product is for — a solution, a database dump, a grading script.
 *
 * Two scopes, because the two kinds of content want opposite defaults and nobody wants to set them
 * together: **markdown wraps by default, code does not.** Each is remembered in this browser only,
 * per person, and applies to every editor of that kind at once — a wrap setting that had to be set
 * again in each of nine editors would not be a setting.
 *
 * Live, not on reload: the extension sits in a [Compartment], so flipping the switch reconfigures
 * the running editor instead of rebuilding it. Rebuilding would drop the cursor, the selection, the
 * scroll position and the undo history, which is a heavy price for a display preference.
 */
import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import { Compartment, type Extension } from '@codemirror/state'
import { EditorView } from '@codemirror/view'

export type WrapScope = 'markdown' | 'code'

const STORAGE_KEY: Record<WrapScope, string> = {
  markdown: 'editor.softWrap.markdown',
  code: 'editor.softWrap.code',
}

const FALLBACK: Record<WrapScope, boolean> = {
  markdown: true,
  code: false,
}

/**
 * Read once and remembered, for two reasons: `useSyncExternalStore` calls the snapshot on every
 * render and a `localStorage` read per render per editor is a waste, and — the sharper one — a
 * snapshot that throws in a browser with site data blocked would take the page down rather than
 * fall back.
 */
const current: Partial<Record<WrapScope, boolean>> = {}

const listeners = new Set<() => void>()

function read(scope: WrapScope): boolean {
  if (current[scope] === undefined) {
    try {
      const stored = localStorage.getItem(STORAGE_KEY[scope])
      current[scope] = stored === null ? FALLBACK[scope] : stored === '1'
    } catch {
      current[scope] = FALLBACK[scope]
    }
  }
  return current[scope]
}

export function setSoftWrap(scope: WrapScope, value: boolean) {
  current[scope] = value
  try {
    localStorage.setItem(STORAGE_KEY[scope], value ? '1' : '0')
  } catch { /* the setting still holds for this session */ }
  listeners.forEach((notify) => notify())
}

function subscribe(notify: () => void) {
  listeners.add(notify)
  return () => { listeners.delete(notify) }
}

/**
 * The wrap setting for `scope`, plus the extension to hand CodeMirror and a toggle for whatever
 * control offers it.
 *
 * Pass `viewRef` — the ref the component already keeps its `EditorView` in — and a change applies
 * to that view immediately. A component without one can leave it out and put `wrap` in the deps of
 * the effect that builds its view; a read-only snippet being rebuilt when the setting changes costs
 * nothing worth naming.
 *
 * `wrapExtension()` is a function rather than a value so that it reads the setting at the moment
 * the view is built. As a value it would be captured by the effect that creates the editor, and a
 * view rebuilt for some other reason — a theme change — would silently come back with whatever the
 * setting had been on the render that effect last closed over.
 */
export function useSoftWrap(
  scope: WrapScope,
  viewRef?: { current: EditorView | null },
) {
  const wrap = useSyncExternalStore(
    subscribe,
    () => read(scope),
    () => FALLBACK[scope],
  )

  // Lazy `useState` rather than a ref filled in on first render: one compartment per editor, made
  // once, and reading or writing a ref during render is the thing the compiler lint objects to.
  const [compartment] = useState(() => new Compartment())

  // On mount this has nothing to do — the view is built from `wrapExtension()`, which already
  // reads the setting — and the view may not exist yet anyway, since it is created in an effect of
  // its own that runs after this one. It earns its keep only when the setting changes under a
  // living editor, which is exactly when the view is there to receive it.
  useEffect(() => {
    viewRef?.current?.dispatch({
      effects: compartment.reconfigure(wrap ? EditorView.lineWrapping : []),
    })
  }, [wrap, compartment, viewRef])

  const wrapExtension = useCallback(
    (): Extension => compartment.of(read(scope) ? EditorView.lineWrapping : []),
    [compartment, scope],
  )

  const toggleWrap = useCallback(() => setSoftWrap(scope, !read(scope)), [scope])

  return { wrap, wrapExtension, toggleWrap }
}
