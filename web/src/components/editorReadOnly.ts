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
 *
 * No caret. Focus brings `drawSelection`'s cursor with it, and a blinking caret says "type here"
 * about text nobody can type in. The selector repeats the library's own, which is the specificity
 * it takes to win. What a keyboard user gets instead is the app's focus ring (`theme.ts`), which
 * already reaches the content element — moved inside it here, because the theme draws it 2px
 * outside and the scroller clips that.
 *
 * `:focus-visible` alone does not keep the ring off a click: measured in Chrome, the content
 * element matches it after a mouse click, which a bare `tabindex` div on a test page does not. So
 * a click is remembered for as long as the focus it gave lasts. On the outer element, as a data
 * attribute: CodeMirror rewrites `class` on both elements and watches the content one for changes.
 */
export const readOnlyEditor: Extension = [
  EditorState.readOnly.of(true),
  EditorView.editable.of(false),
  EditorView.contentAttributes.of({ tabindex: '0' }),
  keymap.of([{ key: 'Mod-a', run: selectAll }]),
  EditorView.domEventHandlers({
    mousedown(_, view) {
      view.dom.dataset.easyPointerFocus = ''
    },
    blur(_, view) {
      delete view.dom.dataset.easyPointerFocus
    },
  }),
  EditorView.theme({
    '&.cm-focused > .cm-scroller > .cm-cursorLayer .cm-cursor': { display: 'none' },
    '.cm-content:focus-visible': { outlineOffset: '-2px' },
    '&[data-easy-pointer-focus] .cm-content:focus-visible': { outline: 'none' },
  }),
]
