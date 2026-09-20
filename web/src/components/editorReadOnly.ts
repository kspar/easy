import { selectAll } from '@codemirror/commands'
import { EditorState, type Extension } from '@codemirror/state'
import { EditorView, keymap } from '@codemirror/view'

/**
 * A read-only editor that can still hold focus, shared by every editor nobody types in (EZ-1920).
 *
 * `editable.of(false)` takes `contenteditable` off the content element, and with it the only
 * reason a browser would focus one. A click still moved CodeMirror's own selection, so the line
 * lit up the way a focused line does — and then Cmd/Ctrl+A selected the whole page, because no key
 * event ever reached the editor. A `tabindex` is what CodeMirror asks for in this case: its
 * selection sync treats `editable || tabIndex > -1` alike, so the selection, copy and the arrow
 * keys all work from there. It also makes the editor a tab stop, which a region that scrolls
 * should be regardless.
 *
 * Select-all is bound here rather than left to `basicSetup`, because not every read-only editor
 * has one — the similarity diff is built from parts and has no keymap at all. Bound twice is
 * harmless where it does.
 */
export const readOnlyEditor: Extension = [
  EditorState.readOnly.of(true),
  EditorView.editable.of(false),
  EditorView.contentAttributes.of({ tabindex: '0' }),
  keymap.of([{ key: 'Mod-a', run: selectAll }]),
]
