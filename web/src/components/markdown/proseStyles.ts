import type { Theme } from '@mui/material'
import type { SystemStyleObject } from '@mui/system'

/**
 * The styling for author-written content rendered from Markdown — exercise statements, articles,
 * feedback, comments.
 *
 * ## Why this exists
 *
 * Until EZ-1729 the old UI styled this content with `wui/static/css/exercise.css`. That file was
 * deleted with the UI it belonged to, and nothing replaced it: `web/` has no stylesheet, and MUI's
 * `CssBaseline` sets a font on `body` and stops. It does not touch `pre`, `code`, `table`,
 * `blockquote`, `details`, `figure` or headings, so all of them rendered at user-agent defaults —
 * `th` with 1px of padding and no border, `code` at body size with no tint, `pre` with no container
 * and, worse, no `overflow-x`.
 *
 * That last one was not merely cosmetic. `CourseExercisePage` renders the statement in a sticky
 * pane roughly 640px wide; a single long code line or a 900px figure made the *pane* 1014px wide
 * and the whole statement scrolled sideways. The rules that stop that — `overflow-x` on `pre` and
 * `table`, `max-width` on `img`, `overflow-wrap` on inline `code` — are the load-bearing part of
 * this file. The rest is typography.
 *
 * ## Why it lives here rather than at the call sites
 *
 * It used to live at the call sites, and that is precisely how the regression stayed invisible.
 * There are thirteen `RenderedMarkdown` call sites across seven files, and they had converged on
 * five different recipes: the library, article and embed pages each set `img`/`pre`/`table`
 * overflow by hand, the feedback and comment views set `p:first-of-type` margins and a font size,
 * and the one that mattered most — the student exercise statement — passed nothing at all. A
 * default that every caller inherits cannot be forgotten by the fourteenth.
 *
 * Callers keep their `sx` and it still wins: `RenderedMarkdown` spreads this first and the caller's
 * after, which is MUI's documented ordering for exactly this. What belongs in a caller now is
 * genuine local variation — the 0.85rem density of a comment bubble — not overflow safety.
 *
 * ## Every colour comes from the theme, deliberately
 *
 * Dark mode currently works by accident: nothing sets a background, so the content inherits the
 * dark palette and stays readable. Adding surfaces is what puts that at risk, so every value here
 * is a token (`action.hover`, `divider`, `text.secondary`, `primary`) rather than a literal. A
 * hard-coded `#f4f6f2` code block would read as a bright slab on the dark ground.
 *
 * `action.hover` is the app's existing idiom for a subtle raised surface — it is what
 * `ReadOnlyCodeSnippet` puts behind code, so exercise code blocks match the code display the
 * student already sees elsewhere on the same page.
 *
 * ## The asciidoc-conversion leftovers are load-bearing
 *
 * `.formalpara > .title` and `.informalexample` are not legacy cruft to be tidied away. They are
 * what the adoc-to-markdown conversion left in the stored HTML, they appear in 109 and 122 of the
 * 1059 live exercises respectively, and there is no Markdown source that would regenerate them
 * differently. Without the rules below, a `formalpara` caption ("Konstandi väljastamine:") renders
 * as an orphaned sentence indistinguishable from body text. `MarkdownService`'s safelist keeps
 * `class` for this reason; this is the other half of that decision.
 */
export function proseStyles(theme: Theme): SystemStyleObject<Theme> {
  const isDark = theme.palette.mode === 'dark'
  // The shade rule from EZ-1798, applied rather than reinvented: `primary.main` is the one green
  // everywhere, except for small green text on a dark surface — the single pairing it cannot carry
  // at AA — where `primary.light` stands in. Links and summary labels here are exactly that case,
  // so they are the rule's subject rather than an exception to it.
  //
  // This deliberately does *not* use `primary.dark`. It was `primary.dark` while `main` was
  // GREEN[600]; EZ-1798 moved `main` to GREEN[700] and `dark` to GREEN[800], which would leave the
  // links in exercise text a shade darker than every other green in the app for no reason anyone
  // could find later.
  const linkColor = isDark ? theme.palette.primary.light : theme.palette.primary.main

  return {
    lineHeight: 1.65,
    // Long unbroken content — a URL, a path — wraps rather than widening the pane.
    overflowWrap: 'break-word',

    // ## Spacing is bottom-margins-only, and that is deliberate
    //
    // Every block below sets `mb` and leaves `mt` at zero, so the first child has no top margin by
    // construction and the block sits flush against the top of its pane or comment bubble — the
    // job the `& p:first-of-type: { mt: 0 }` at five separate call sites was doing, but for
    // content that starts with a heading, a list or a code block too, which those missed.
    //
    // The alternative, a `:first-child` reset, is what this replaces: Emotion warns on
    // `:first-child` and `:nth-child` (both were measured — the `:nth-of-type` it suggests is not
    // equivalent here, because the content's first element can be any tag), and the warning fires
    // on every render of every exercise statement. Bottom-margins-only needs no such reset.
    //
    // Only the closing edge needs a rule, and `:last-child` is the one child pseudo-class Emotion
    // does not warn about.
    '& > :last-child': { mb: 0 },

    '& p': { mt: 0, mb: '0.85em' },

    /* ── Headings ─────────────────────────────────────────────────────── */
    // Sized against the app's own type scale rather than the UA's, and tightened: these sit inside
    // a narrow pane, where the UA's 2em h1 is far too loud for a section label.
    '& h1, & h2, & h3, & h4, & h5, & h6': {
      fontWeight: 600,
      lineHeight: 1.3,
      letterSpacing: '-0.005em',
      mt: 0,
      mb: '0.6em',
    },
    // A heading needs air above it, but only when something precedes it — the adjacent-sibling
    // combinator gives exactly that, and leaves a leading heading flush without a reset rule.
    //
    // **The `*` is load-bearing and `:where()` cannot replace it.** The first version wrapped both
    // sides in `:where()` to keep the specificity at zero, which quietly disabled the whole rule:
    // `:where()` contributes nothing, so that selector scored (0,1,0) against the (0,1,1) of the
    // `& h1, & h2, …` block above, and the `mt: 0` there won every time. Every heading sat flush
    // against the paragraph before it — the exact spacing these two rules exist to produce.
    // `* + h1` scores (0,1,1), ties, and wins on being declared later.
    '& * + h1, & * + h2, & * + h3, & * + h4, & * + h5, & * + h6': { mt: '1.75em' },
    '& h1': { fontSize: '1.5rem' },
    '& h2': { fontSize: '1.25rem' },
    '& h3': { fontSize: '1.05rem' },
    '& h4, & h5, & h6': { fontSize: '1rem', color: 'text.secondary' },

    /* ── Links ────────────────────────────────────────────────────────── */
    // Was the raw user-agent blue (measured `rgb(0, 0, 238)`) in a green-branded app.
    '& a': {
      color: linkColor,
      textDecoration: 'underline',
      textUnderlineOffset: '2px',
      textDecorationThickness: '1px',
      '&:hover': { textDecorationThickness: '2px' },
    },

    /* ── Inline code ──────────────────────────────────────────────────── */
    '& code': {
      fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
      // Monospace runs visually larger than the body face at the same size, so this is a
      // correction rather than a de-emphasis.
      fontSize: '0.875em',
      bgcolor: 'action.hover',
      borderRadius: '4px',
      px: '0.34em',
      py: '0.12em',
      // A long identifier has to break rather than push the pane wider.
      overflowWrap: 'anywhere',
    },

    /* ── Code blocks ──────────────────────────────────────────────────── */
    '& pre': {
      mt: 0,
      mb: 2,
      px: 1.75,
      py: 1.5,
      bgcolor: 'action.hover',
      border: 1,
      borderColor: 'divider',
      // A literal, not the `borderRadius: 2` shorthand: the app's `shape.borderRadius` is 12, so
      // the shorthand multiplies to a 24px radius that turns a code block into a lozenge.
      borderRadius: '8px',
      // The rule whose absence scrolled the entire statement sideways.
      overflowX: 'auto',
      // `em`, not `rem`, so the caller's density reaches it. The comment and feedback views pass
      // `fontSize: '0.8rem'`/`'0.85rem'`; against a fixed `rem` a code block in a 12.8px comment
      // rendered at 14px — larger than the prose around it, which is the inverse of the intent.
      fontSize: '0.875em',
      lineHeight: 1.6,
      tabSize: 4,
    },
    // Nested `code` must not tint or pad a second time, and must not inherit the wrapping that
    // inline code wants — inside a block, a broken line changes what the code means.
    '& pre code': {
      bgcolor: 'transparent',
      p: 0,
      fontSize: 'inherit',
      overflowWrap: 'normal',
      whiteSpace: 'pre',
    },

    // ## A fenced block with no language is not source, and is styled as such
    //
    // The corpus draws this distinction and nothing has ever shown it. Of the 1624 code blocks in
    // the live exercises, 1133 carry a `language-*` class and 491 do not — and the untagged ones
    // are overwhelmingly not code: sampling 484 of them found 3 that look like Python source, 42
    // Thonny transcripts (`>>> %Run lahendus.py`), and the rest program output or the contents of
    // an input file (`tihane,7`, `varblane,5`).
    //
    // So these get the opposite emphasis from source: no fill, a dashed edge and secondary text —
    // "verbatim content" rather than "code to write". A console treatment was the other candidate
    // and was rejected on two counts: a terminal is the wrong metaphor for roughly a third of
    // these, which are file listings rather than output, and a dark slab inverts between palettes,
    // becoming the loudest thing on a light page and the quietest on a dark one. This stays
    // theme-derived, so its relationship to the source block is the same in both.
    //
    // `:has()` is what makes the rule expressible in CSS at all, and it is doing real work here —
    // the check is on the *child* `code`, so `pre > code` with no class matches and
    // `pre > code.language-python` does not.
    '& pre:has(> code:not([class]))': {
      bgcolor: 'transparent',
      borderStyle: 'dashed',
      color: 'text.secondary',
    },

    /* ── Syntax highlighting ──────────────────────────────────────────── */
    // The token colours for `highlightCode`. Written here rather than by importing one of
    // highlight.js's stylesheets, because every one of those is a single palette: dropping
    // `github.css` in would light up correctly and then render dark-purple keywords on the dark
    // theme's near-black code block. These are two palettes chosen against the two grounds
    // `action.hover` actually produces.
    //
    // The scheme is deliberately narrow — five hues, no backgrounds, no bold beyond keywords.
    // Exercise code is read next to the statement that explains it, so it should sit quieter than
    // an editor's theme would.
    // Every value here was measured against the ground it actually sits on — `action.hover` over
    // the paper colour, which is `#f5f5f5` in light — and clears WCAG AA 4.5:1 there. Two did not
    // on the first pass and are corrected: the light orange was `#b26500` (4.04:1), and comments
    // took `text.secondary`, which is `#757575` in this theme (4.22:1). Numbers occur in nearly
    // every Python snippet and comments in most teaching ones, so these were not edge cases. The
    // repo tracks `color-contrast` in `tests/a11y-baseline.json`; keep new tokens above 4.5:1.
    ...(isDark
      ? {
          '& .hljs-keyword, & .hljs-literal, & .hljs-type': { color: '#ce93d8' },
          '& .hljs-string, & .hljs-attr': { color: '#4db6ac' },
          '& .hljs-number, & .hljs-meta, & .hljs-variable': { color: '#ffb74d' },
          '& .hljs-title, & .hljs-built_in, & .hljs-name, & .hljs-tag': { color: '#64b5f6' },
          // `fontStyle` is repeated in both branches rather than set once below, because an
          // object literal replaces a duplicate key outright — a later `'& .hljs-comment'` entry
          // would drop the colour this one sets, not merge with it.
          '& .hljs-comment': { color: '#9aa5a0', fontStyle: 'italic' },
        }
      : {
          '& .hljs-keyword, & .hljs-literal, & .hljs-type': { color: '#7b1fa2' },
          '& .hljs-string, & .hljs-attr': { color: '#00695c' },
          '& .hljs-number, & .hljs-meta, & .hljs-variable': { color: '#9a5800' },
          '& .hljs-title, & .hljs-built_in, & .hljs-name, & .hljs-tag': { color: '#1565c0' },
          '& .hljs-comment': { color: '#5f6b62', fontStyle: 'italic' },
        }),
    '& .hljs-keyword': { fontWeight: 600 },

    /* ── Collapsible hints ────────────────────────────────────────────── */
    // 436 of the 1059 live exercises hang a hint here, which makes this the most-used interactive
    // element in the corpus. It rendered as plain body text behind the UA's disclosure triangle:
    // nothing marked it as something to click.
    '& details': {
      mt: 0,
      mb: 2,
      border: 1,
      borderColor: 'divider',
      // Literal for the same reason as `pre` above.
      borderRadius: '8px',
      overflow: 'hidden',
    },
    '& details[open]': { pb: 1 },
    '& summary': {
      cursor: 'pointer',
      px: 1.75,
      py: 1,
      fontWeight: 500,
      // The same `linkColor` the links use, for the same reason: the green this started with
      // measured 3.3:1 on paper and 3.0:1 on the hover ground this rule paints, both under AA.
      // This is the affordance on the most-used interactive element in the corpus, so it is the
      // worst place in the content to be hard to read.
      color: linkColor,
      userSelect: 'none',
      // The UA marker is replaced by the rotating chevron below. Both properties are needed:
      // Safari and older WebKit only honour the pseudo-element.
      listStyle: 'none',
      '&::-webkit-details-marker': { display: 'none' },
      '&:hover': { bgcolor: 'action.hover' },
      // The focus ring is pulled inside the box rather than sitting 2px outside it, because
      // `details` clips (see `overflow` above) and would otherwise cut off the top and outer edges
      // of the ring on the one element here a keyboard user actually tabs to.
      '&:focus-visible': { outlineOffset: '-2px' },
      // **Deliberately not a flex container.** Flex was the obvious way to place the chevron and it
      // breaks real content: every contiguous text run in a summary becomes its own anonymous flex
      // item, so `<summary>Näide, mis kasutab <code>randint</code></summary>` — a shape the corpus
      // genuinely contains — lays out as three items separated by the `gap` rather than by spaces,
      // and cannot wrap. An inline-block marker leaves the summary in normal inline flow, where
      // mixed text and markup behave the way they do everywhere else.
      '&::before': {
        content: '""',
        display: 'inline-block',
        width: '0.4em',
        height: '0.4em',
        mr: 1,
        // The rotation is applied about the box's own centre, and `transform` does not affect
        // layout, so the reserved space stays 0.4em square whichever way the chevron points.
        verticalAlign: 'middle',
        borderRight: '2px solid currentColor',
        borderBottom: '2px solid currentColor',
        transform: 'translateY(-0.1em) rotate(-45deg)',
        transition: theme.transitions.create('transform', { duration: 150 }),
      },
    },
    '& details[open] > summary::before': { transform: 'translateY(-0.2em) rotate(45deg)' },
    // The corpus puts content directly inside `details` with no wrapper, so the inset goes on the
    // children. (The old CSS styled `details > .content`, a class the Markdown pipeline never
    // emits — a rule that would have silently matched nothing.)
    '& details > :not(summary)': { mx: 1.75 },
    // A gap between the summary and whatever opens below it. Without it the two run together the
    // moment the pointer is over the header: the summary's hover state and a code block are both
    // painted in `action.hover`, they meet with no seam, and the result reads as one shape whose
    // top half happens to be clickable. The blocks that open a collapsible are usually exactly the
    // ones that carry that background — an example or a snippet — so this is the common case, not
    // an edge one.
    //
    // The selector is `details > summary + *` rather than `summary + *` deliberately: it scores
    // (0,1,2) and so beats the `mt: 0` that `& pre` sets at (0,1,1) whatever order they end up in.
    // The equivalent rule written as `& summary + *` would tie on specificity and depend on source
    // order — which is the way the heading margins were silently lost once already.
    '& details > summary + *': { mt: 1 },

    /* ── Quotes ───────────────────────────────────────────────────────── */
    // The UA gives this nothing but `margin: 16px 40px`, so it read as an accidentally indented
    // sentence rather than a callout.
    '& blockquote': {
      mt: 0,
      mb: 2,
      mx: 0,
      px: 2,
      py: 1,
      borderLeft: 3,
      borderColor: 'primary.main',
      borderRadius: '0 8px 8px 0',
      bgcolor: 'action.hover',
      '& > :last-child': { mb: 0 },
    },

    /* ── Tables ───────────────────────────────────────────────────────── */
    '& table': {
      mt: 0,
      mb: 2,
      borderCollapse: 'collapse',
      // `display: block` is what makes `overflow-x` apply at all — a `display: table` box sizes to
      // its content and ignores it, so a wide table would widen the pane instead of scrolling.
      display: 'block',
      width: 'max-content',
      maxWidth: '100%',
      overflowX: 'auto',
      // `em` for the same reason as `pre` above.
      fontSize: '0.9375em',
    },
    '& th, & td': {
      border: 1,
      borderColor: 'divider',
      px: 1.5,
      py: 0.75,
      // The UA centres and bolds `th`; centring a text header just makes columns harder to scan.
      textAlign: 'left',
      verticalAlign: 'top',
    },
    '& thead th': {
      bgcolor: 'action.hover',
      fontWeight: 600,
      whiteSpace: 'nowrap',
    },
    '& caption': {
      captionSide: 'bottom',
      pt: 1,
      fontSize: '0.85em',
      color: 'text.secondary',
    },

    /* ── Media ────────────────────────────────────────────────────────── */
    '& img': {
      display: 'block',
      maxWidth: '100%',
      height: 'auto',
      borderRadius: '6px',
    },
    // The UA's `margin: 1em 40px` on `figure` is the source of the unexplained indent on every
    // converted image — the conversion wrapped them all in `<figure>`.
    '& figure': { mt: 0, mb: 2, mx: 0 },
    '& figcaption': {
      mt: 0.5,
      fontSize: '0.85em',
      color: 'text.secondary',
      textAlign: 'center',
    },

    /* ── Lists ────────────────────────────────────────────────────────── */
    // The UA's 40px indent is expensive in a pane this narrow.
    '& ul, & ol': { mt: 0, mb: '0.85em', pl: 3 },
    '& li': { mt: 0, mb: '0.3em' },
    '& li > ul, & li > ol': { mt: '0.3em', mb: 0 },
    // Distinguishable nesting levels; the UA already does this, but `list-style` is easy to lose
    // and the corpus nests two deep in places.
    '& ul ul': { listStyleType: 'circle' },
    '& ul ul ul': { listStyleType: 'square' },

    /* ── Asciidoc-conversion leftovers ────────────────────────────────── */
    // A caption for the block beneath it — 109 live exercises. Without this it is a bare `<p>`.
    '& .formalpara': { mt: 0, mb: 2 },
    '& .formalpara > .title': {
      fontSize: '0.875rem',
      fontWeight: 600,
      color: 'text.secondary',
      mb: 0.5,
      '& p': { m: 0 },
    },
    // The caption belongs to the block below, so it must not be pushed away from it.
    '& .formalpara > .title + pre': { mt: 0 },
    '& .informalexample': {
      mt: 0,
      mb: 2,
      '& > :last-child': { mb: 0 },
    },
    // The converter recorded centring as `role="text-center"`. It is not a valid ARIA role and is
    // worth fixing at the source, but until then honouring it beats rendering it as a no-op.
    '& [role="text-center"]': {
      textAlign: 'center',
      '& img, & figure': { marginInline: 'auto' },
    },
    '& table[role="center"]': { marginInline: 'auto' },

    /* ── Odds and ends ────────────────────────────────────────────────── */
    '& hr': {
      border: 0,
      borderTop: 1,
      borderColor: 'divider',
      mt: 0,
      mb: '1.75em',
    },
    // Keeps a footnote marker or an exponent from opening up the line it sits on.
    '& sub, & sup': { lineHeight: 0 },
    '& kbd': {
      fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
      fontSize: '0.85em',
      border: 1,
      borderBottomWidth: 2,
      borderColor: 'divider',
      borderRadius: '4px',
      px: '0.35em',
      py: '0.1em',
    },
    '& mark': {
      bgcolor: isDark ? 'rgba(255, 213, 79, 0.28)' : 'rgba(255, 235, 59, 0.45)',
      color: 'inherit',
      borderRadius: '3px',
      px: '0.2em',
    },
  }
}
