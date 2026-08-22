/**
 * Typesets the maths that core's Markdown renderer marked up (EZ-1732).
 *
 * Core does not typeset — see `core/ems/service/markdown_math.kt` for why the *parsing* has to
 * happen there — so what arrives in `text_html` is
 *
 * ```html
 * <span class="easy-math" data-easy-math="inline" data-easy-tex="x^2">$x^2$</span>
 * ```
 *
 * and this turns the `data-easy-tex` into KaTeX output. The element text is the original source
 * including its delimiters, which is deliberate: if this module never runs — the import fails, JS is
 * off, a formula is added to a page nobody wired up — the reader sees `$x^2$` rather than a gap.
 * That is also what students saw between the WUI being replaced and this fix, so the floor is
 * "no worse than before" rather than "blank".
 *
 * ## Loaded on demand
 *
 * KaTeX is ~280KB of JS plus a megabyte of web fonts, and the overwhelming majority of pages here
 * have no maths on them at all. So the import happens only once an element has actually been found,
 * and never on a page without one.
 *
 * The cost of that decision is the one recorded in `vite.config.ts` for the CodeMirror language
 * modes: a dependency imported at runtime rather than at startup is one the dev server meets for the
 * first time mid-session, and answers with a 504 while it re-optimises. `katex` is therefore listed
 * in `optimizeDeps.include`. Keep it there — without it this fails only in dev, only on the first
 * exercise with a formula, and looks exactly like maths not being implemented.
 */

/** Set once typeset, so a re-run over the same DOM is cheap rather than merely idempotent. */
const DONE = 'easyMathDone'

let katexPromise: Promise<typeof import('katex').default> | null = null

function loadKatex() {
  // Cached at module scope: several of these components can mount at once — an activity feed
  // renders a comment per row — and each would otherwise start its own import.
  //
  // A *rejection* is not cached, though, which is the whole reason this is a function and not a
  // bare `??=`. A single failed chunk fetch — the usual cause being a deploy rotating hashed
  // filenames under a tab that has been open a while — would otherwise disable maths for the rest
  // of the session on every page, including the teacher's live preview, with nothing to retry it.
  katexPromise ??= Promise.all([import('katex'), import('katex/dist/katex.min.css')])
    .then(([katex]) => katex.default)
    .catch((e: unknown) => {
      katexPromise = null
      throw e
    })
  return katexPromise
}

/**
 * Typesets every untypeset formula under [root]. Safe to call on any element, including one with no
 * maths in it, which is the common case.
 */
export async function renderMath(root: HTMLElement): Promise<void> {
  // A probe, not a list. Holding element references across the `await` below is the bug this
  // function was first written with: something replaced the container's children while the import
  // was in flight, and KaTeX then typeset a set of orphaned nodes perfectly, into a DOM nobody was
  // looking at. Nothing threw, nothing logged, and the page showed `$x^2$`.
  if (root.querySelector('[data-easy-tex]') === null) return

  let katex
  try {
    katex = await loadKatex()
  } catch {
    // A chunk that will not load is not worth retrying per element, and the source text is still
    // sitting there readable. Nothing to say that the page does not already show.
    return
  }

  // Queried after the await, so these are whatever is in the DOM *now*.
  const pending = Array.from(root.querySelectorAll<HTMLElement>('[data-easy-tex]')).filter(
    (el) => !(DONE in el.dataset),
  )

  for (const el of pending) {
    const tex = el.dataset.easyTex
    if (!tex) continue
    try {
      katex.render(tex, el, {
        displayMode: el.dataset.easyMath === 'display',
        // Render the offending source in red rather than throwing. A teacher editing an exercise
        // sees which formula is wrong while they are still in the editor, which is the moment it is
        // cheapest to fix; the alternative is a silent gap that reaches a student.
        throwOnError: false,
        // The TeX is authored by teachers, and on some courses by students in feedback, so it is
        // untrusted input. `false` is the default — stated because it is the line that keeps
        // \href, \url and \includegraphics from turning a formula into a link or a request.
        trust: false,
        // MathML alongside the visual output: it is what a screen reader reads. Dropping it would
        // make every formula on the site silent.
        output: 'htmlAndMathml',
        // Warnings only, and only to the console — a teacher writing Unicode inside `$…$` should
        // get their formula, not a refusal.
        strict: 'ignore',
      })
      el.dataset[DONE] = '1'
    } catch {
      // KaTeX still throws past `throwOnError` for anything that is not a parse error. Leave the
      // element as it was: the delimited source is more use than an empty box.
    }
  }
}
