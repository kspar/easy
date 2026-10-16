/**
 * What the page makes of the language core compared a cohort in (`language` on the response): the file
 * name the diff view highlights the code as, and the note, if any, on how to read the scores.
 *
 * Here rather than in the page because a wrong answer is invisible there. A Java diff highlighted as
 * plain text still renders, only without its grammar; and a missing note is the one thing that would
 * have told a teacher that an SQL score reads differently from a Python one.
 */

/** CodeMirror picks a grammar by extension, so the language is handed over as a file name. */
export function highlightFileName(language: string): string {
  switch (language) {
    case 'python':
      return 'solution.py'
    case 'java':
      return 'Solution.java'
    case 'sql':
      return 'solution.sql'
    default:
      return 'solution.txt'
  }
}

/**
 * `text`: the solutions were compared word by word, a weaker answer than a token one, and the page must
 * not let it read like one. `sql`: compared as code, but names and values are not tokens, so correct
 * answers to a short query share most of theirs whoever wrote them. Anything else needs no note -
 * including a language a newer core compares that this bundle has not heard of, which is still code.
 */
export type LanguageNote = 'text' | 'sql'

export function languageNote(language: string): LanguageNote | null {
  if (language === 'text') return 'text'
  if (language === 'sql') return 'sql'
  return null
}
