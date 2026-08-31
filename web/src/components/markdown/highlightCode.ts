/**
 * Syntax-highlights the code blocks in rendered Markdown.
 *
 * ## This restores something the migration dropped, rather than adding something new
 *
 * The old UI highlighted code: `wui/src/main/kotlin/syntax_highlight.kt` ran highlight.js over
 * every exercise. It selected `pre.highlightjs.highlight code.hljs` — classes **asciidoctor**
 * emitted. When rendering moved to commonmark the markup became `<pre><code class="language-python">`,
 * that selector stopped matching anything, and highlighting disappeared with no error and no
 * failing test. Measured on the current corpus: of the live exercise rows, the 153 legacy ones
 * still carry `hljs`/`highlightjs` classes and the 1059 Markdown-era ones carry none, while 693 of
 * them carry `language-*`. So the hooks are all there; nothing was reading them.
 *
 * ## Loaded on demand, for the same reasons as KaTeX
 *
 * See `renderMath.ts` — this follows its structure deliberately, including the parts that look
 * like belt-and-braces:
 *
 *  - the module-scope promise is cached so that an activity feed rendering a comment per row
 *    starts one import rather than twenty;
 *  - a *rejection* is not cached, so one failed chunk fetch (typically a deploy rotating hashed
 *    filenames under a long-open tab) does not disable highlighting for the rest of the session;
 *  - the probe runs before the import, so a page with no code on it never pays for the library;
 *  - elements are re-queried *after* the await, because the live preview in the exercise editor
 *    replaces this content on every debounce tick and the nodes captured before the await may no
 *    longer be in the document.
 *
 * `highlight.js/lib/core` with four languages registered by hand, rather than the default build:
 * the full package carries ~190 languages, and the corpus uses four. The counts behind that choice
 * are `language-python` 968 (including case and spelling variants), `language-sql` 148,
 * `language-bash` 6 and `language-xml` 2.
 *
 * ## Failure is always silent and always leaves readable text
 *
 * Every failure path here leaves the block exactly as it arrived — monospaced, in its container,
 * fully readable. Highlighting is an enhancement to code that is already legible without it, so
 * there is no error state worth showing a student.
 */

/** Set once highlighted, so re-running over the same DOM is cheap rather than merely idempotent. */
const DONE = 'easyHlDone'

/**
 * Language aliases, lowercased.
 *
 * The corpus is not consistent, and highlight.js matches its language names exactly. Without this
 * map the 22 blocks tagged `language-Python` and the four tagged `language-pytohn`,
 * `language-pyhton`, `language-pyton` and `language-source` would render unhighlighted — a failure
 * with no symptom other than a block that looks slightly plainer than the one above it.
 *
 * The typos are listed individually rather than fuzzy-matched: a fixed list is reviewable, and a
 * fuzzy matcher would eventually decide that some other language was a misspelling of Python.
 */
const ALIASES: Record<string, string> = {
  python: 'python',
  py: 'python',
  // Spelling variants found in the live corpus.
  pytohn: 'python',
  pyhton: 'python',
  pyton: 'python',
  sql: 'sql',
  bash: 'bash',
  sh: 'bash',
  shell: 'bash',
  xml: 'xml',
  html: 'xml',
}

let hljsPromise: Promise<typeof import('highlight.js/lib/core').default> | null = null

function loadHljs() {
  hljsPromise ??= Promise.all([
    import('highlight.js/lib/core'),
    import('highlight.js/lib/languages/python'),
    import('highlight.js/lib/languages/sql'),
    import('highlight.js/lib/languages/bash'),
    import('highlight.js/lib/languages/xml'),
  ])
    .then(([core, python, sql, bash, xml]) => {
      const hljs = core.default
      hljs.registerLanguage('python', python.default)
      hljs.registerLanguage('sql', sql.default)
      hljs.registerLanguage('bash', bash.default)
      hljs.registerLanguage('xml', xml.default)
      return hljs
    })
    .catch((e: unknown) => {
      hljsPromise = null
      throw e
    })
  return hljsPromise
}

/** The language this block is tagged with, or null when it carries no usable `language-*` class. */
function languageOf(el: Element): string | null {
  for (const cls of el.classList) {
    if (!cls.startsWith('language-')) continue
    const raw = cls.slice('language-'.length).toLowerCase()
    // An unknown value returns null rather than being passed through: `highlight()` throws on an
    // unregistered language, and `language-math` (5 blocks) is not a language at all.
    return ALIASES[raw] ?? null
  }
  return null
}

/**
 * Highlights every un-highlighted, language-tagged code block under [root]. Safe to call on any
 * element, including one with no code in it, which is the common case.
 */
export async function highlightCode(root: HTMLElement): Promise<void> {
  // A probe, not a list — see the note in `renderMath` about holding references across the await.
  //
  // It asks `languageOf`, not just the selector, because the two disagree in a case the corpus
  // actually has: `language-math` is tagged but not a language, so a page whose only tagged block
  // is a formula would download the whole highlighter and then highlight nothing.
  const anyHighlightable = Array.from(
    root.querySelectorAll('pre > code[class*="language-"]'),
  ).some((el) => languageOf(el) !== null)
  if (!anyHighlightable) return

  let hljs
  try {
    hljs = await loadHljs()
  } catch {
    // The code is still perfectly readable unhighlighted. Nothing to report.
    return
  }

  const pending = Array.from(
    root.querySelectorAll<HTMLElement>('pre > code[class*="language-"]'),
  ).filter((el) => !(DONE in el.dataset))

  for (const el of pending) {
    const language = languageOf(el)
    if (!language) continue
    // A canary inside a code sample is a natural place to put one, and highlighting would destroy
    // it: the block is re-emitted from `textContent`, so the `easy-hidden` element disappears and
    // its text comes back as ordinary highlighted source — in plain sight. Leaving such a block
    // unhighlighted is the smaller loss, and it is the same fallback the catch below settles for.
    if (el.querySelector('easy-hidden')) continue
    try {
      // `textContent`, not `innerHTML`: the block's text is the authored source, and taking it as
      // text means nothing an author wrote can re-enter the DOM as markup on this path. What goes
      // back in is highlight.js's own output, which escapes the input it was given.
      const source = el.textContent ?? ''
      if (!source.trim()) continue
      el.innerHTML = hljs.highlight(source, { language, ignoreIllegals: true }).value
      el.dataset[DONE] = '1'
    } catch {
      // Leave the block as it was — the unhighlighted source is the point, not a fallback.
    }
  }
}
