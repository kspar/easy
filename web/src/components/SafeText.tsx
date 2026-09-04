/**
 * Changing text, wrapped so that a browser page translator cannot crash the route.
 *
 *     {isSubmitting ? <CircularProgress size={18} /> : <SafeText>{t('general.save')}</SafeText>}
 *
 * ## The crash this exists to stop
 *
 * Four bug reports in two days (EZ-1888, from EZ-1884 and its three siblings), three students, all
 * the same exception and all on the same click:
 *
 *     NotFoundError: Failed to execute 'removeChild' on 'Node':
 *     the node to be removed is not a child of this node
 *
 * Every one of them landed 1.5–3.6 s after `submission accepted; waiting for auto-assessment`, at
 * the moment `AutogradeAnimation`'s status line swapped "Kompileerin…" for "Valmis". The route
 * error boundary took the page down with it, so the student never saw the grade the grader had
 * already produced — two of them resubmitted over and over trying to get past it.
 *
 * The mechanism is Chrome's translate feature, and the shape of the JSX is what makes it fatal.
 * When the translator rewrites a phrase it **replaces the text node** with `<font>` elements
 * carrying the translation. React still holds a reference to the text node it created. That is
 * harmless right up to the point where React wants to *delete* that text node while its parent
 * survives — which is exactly what
 *
 *     {isCompleted ? t('submission.autogradeDone') : <>{statusMessages[phase]}<Caret /></>}
 *
 * asks for: the two branches have different shapes, so React deletes the fragment's children
 * individually, calling `parent.removeChild(textNode)` on a node the translator detached minutes
 * ago. The DOM throws, the throw escapes render, and the route is gone.
 *
 * ## Why one `<span>` is the whole fix
 *
 * It changes what React's deletion target *is*. A translator rewrites what is inside a `<span>` but
 * leaves the span itself in place, so `removeChild(span)` still finds its child — the failure mode
 * is gone rather than mitigated.
 *
 * It also repairs the translated content on the next change, which is the part worth knowing
 * because it is not obvious: for a host element whose children are a *single* string, React does
 * not update the text node it remembers — it assigns `textContent` on the element. That wipes
 * whatever the translator put inside and writes a fresh text node. So a `SafeText` whose text
 * changes comes back correct, translation and all, with no bookkeeping.
 *
 * Both of those depend on this component rendering exactly one string child and nothing else. An
 * earlier version keyed the span on its content (`<span key={children}>`) to force a replacement
 * instead, on the theory that React would otherwise write to the detached node and the status line
 * would silently freeze. It would not, and the browser test proves it either way: the key was
 * removed once the DOM before and after a phase change was actually read rather than reasoned
 * about. If this ever grows a second child, that reasoning stops holding and the freeze becomes
 * real — so keep it to one.
 *
 * ## Why not `translate="no"`
 *
 * It would also stop the crash, and it was the first fix written here. It is the wrong one: it
 * works by *denying* translation, and the students in those four reports are exactly the people
 * relying on it — an Estonian-language UI read through Chrome's translator. Fixing their crash by
 * taking away their translation is not a fix. This keeps the page translatable and makes React's
 * bookkeeping survive it.
 *
 * ## Where it is needed
 *
 * Anywhere changing text is a direct child of an element that outlives the change. There are two
 * grades of it, and both are worth wrapping:
 *
 *  - **Text that swaps for markup** — `{saving ? <CircularProgress /> : t('general.save')}`, or a
 *    sentence beside a conditional second sentence. React deletes a text node out of a surviving
 *    parent, and this *crashes*. That is EZ-1888.
 *  - **Text that swaps for other text** — `{saving ? t('general.saving') : t('general.save')}`.
 *    React updates in place, so on a translated page the write lands on the detached node and the
 *    label silently never changes: a confirm button that never says "Removing…", a status line
 *    frozen on one phase. No crash, no error, no feedback.
 *
 * A MUI `Button`'s children are an array (`{startIcon}{children}{endIcon}`), so a bare string
 * label there is a real text node in both cases — which is why the buttons in this app are worth
 * going through even where a plain `<Typography>{string}</Typography>` would not be. That one is
 * genuinely safe: a single string child is updated by assigning `textContent` on the element, so
 * it repairs itself.
 */
export default function SafeText({ children }: { children: string }) {
  return <span>{children}</span>
}
