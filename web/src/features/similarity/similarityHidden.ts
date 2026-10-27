import { Decoration, EditorView, MatchDecorator, ViewPlugin, highlightSpecialChars } from '@codemirror/view'
import type { DecorationSet, ViewUpdate } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import type { Extension } from '@codemirror/state'

/**
 * What a copy can be hidden behind that a reader cannot see (EZ-1667): characters that print as nothing -
 * zero-width spaces and joiners, direction marks, control characters - and names that mix Latin letters
 * with Cyrillic or Greek ones drawn the same. A copy salted with zero-width characters in its comments, or
 * with "а" for "a" in its names, read on the page as the very program it was copied from, while it moved
 * a text comparison's score, and nothing said why two identical-looking solutions were not identical.
 *
 * Neither has a use in an ordinary solution, so each is marked where it is and counted above the code.
 * Not every non-Latin letter: a comment in Russian is a comment in Russian. Only a word that mixes the
 * alphabets, which no language writes. And not a byte order mark at the very start: Notepad writes one.
 */

/** Characters that print as nothing: format characters, and control characters but tab and line breaks. */
const INVISIBLE = /(?![\t\n\r])[\p{Cf}\p{Cc}]/gu
const BOM = '﻿'

/** How many characters in [text] print as nothing; see [INVISIBLE]. */
export function invisibleCount(text: string): number {
  let count = 0
  for (const match of text.matchAll(INVISIBLE)) if (!(match.index === 0 && match[0] === BOM)) count++
  return count
}

const LATIN = /\p{Script=Latin}/u
const LOOKALIKE = /[\p{Script=Cyrillic}\p{Script=Greek}]/u
const WORD_SOURCE = '[\\p{L}\\p{M}\\p{N}_]+'

/** Whether [word] mixes Latin letters with Cyrillic or Greek ones: one alphabet is a word, two a disguise. */
export function mixesScripts(word: string): boolean {
  return LATIN.test(word) && LOOKALIKE.test(word)
}

/** The distinct words in [text] that mix alphabets, in the order they first appear. */
export function mixedScriptWords(text: string): string[] {
  const found = new Set<string>()
  for (const [word] of text.matchAll(new RegExp(WORD_SOURCE, 'gu'))) if (mixesScripts(word)) found.add(word)
  return [...found]
}

/** What one solution hides: characters that print as nothing, and words that mix alphabets. */
export interface Hidden {
  invisible: number
  mixed: string[]
}

export function hiddenIn(text: string | null | undefined): Hidden {
  const solution = text ?? ''
  return { invisible: invisibleCount(solution), mixed: mixedScriptWords(solution) }
}

export function hidesAnything(hidden: Hidden | undefined): boolean {
  return hidden !== undefined && (hidden.invisible > 0 || hidden.mixed.length > 0)
}

const mixedScriptStyle = EditorView.baseTheme({
  '.cm-mixedScript': { textDecoration: 'underline wavy #d32f2f', textUnderlineOffset: '3px' },
})

/**
 * Both marked in a read-only editor: each invisible character as CodeMirror's placeholder, a red dot whose
 * title names it ([invisibleTitle] in place of its "Control character"), and each word mixing alphabets
 * underlined, titled [mixedTitle]. Without it the merge view drew a zero-width space as nothing at all.
 */
export function hiddenMarking(invisibleTitle: string, mixedTitle: string): Extension {
  const mixed = Decoration.mark({ class: 'cm-mixedScript', attributes: { title: mixedTitle } })
  const decorator = new MatchDecorator({
    regexp: new RegExp(WORD_SOURCE, 'gu'),
    decorate: (add, from, to, match) => {
      if (mixesScripts(match[0])) add(from, to, mixed)
    },
  })
  return [
    // The default set already has the zero-width space, direction overrides and controls; this adds
    // the rest of the format characters - joiners, the word joiner, the other direction marks.
    highlightSpecialChars({ addSpecialChars: /\p{Cf}/u }),
    EditorState.phrases.of({ 'Control character': invisibleTitle }),
    ViewPlugin.fromClass(
      class {
        decorations: DecorationSet
        constructor(view: EditorView) {
          this.decorations = decorator.createDeco(view)
        }
        update(update: ViewUpdate) {
          this.decorations = decorator.updateDeco(update, this.decorations)
        }
      },
      { decorations: (plugin) => plugin.decorations },
    ),
    mixedScriptStyle,
  ]
}
