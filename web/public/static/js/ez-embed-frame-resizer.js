/*
 * Parent-side half of the embed auto-resizer.
 *
 * An embedded exercise cannot size its own iframe, so the embedded page measures itself and posts
 * its height here; this listener finds the matching iframe and sets it. It stays at the URL wui
 * served it from and keeps reading wui's `ez-frame-resize` message, because embeds already
 * published in PmWiki and elsewhere have this script tag baked into them — changing the URL or the
 * message format would silently break every one of them, and they are on pages nobody here can
 * edit.
 *
 * The page half lives in web/src/features/embed/EmbedExercisePage.tsx.

 *
 * ## Why the iframe is found by comparing parsed URLs
 *
 * It used to be `document.querySelector('iframe[src="' + decodeURI(path) + '?' + query + '"]')`,
 * an exact match against the attribute text. That works only while every published `src` is spelled
 * the way `decodeURI` leaves the URL the frame reports — which was true of wui's snippets, whose
 * slugs were readable (`Koduülesanne-1.2-Arvutamine`), and false of v4's, which percent-encoded the
 * title (`Kodu%C3%BClesanne%201.2%20Arvutamine`). `decodeURI` then stripped an encoding layer that
 * was really there, the selector matched nothing, and `null.setAttribute` threw into the empty
 * `catch` below. Every embed published from v4 sat at the iframe default of 150px with the exercise
 * cut off (EZ-1831).
 *
 * Both spellings parse to the same `URL.href`, so comparing parsed URLs fixes the pages already out
 * there without anyone editing them — which matters, because the generator fix reaches only embeds
 * pasted from now on. It also drops the last assumption about how a `src` is written: relative URLs,
 * a different but equivalent encoding, and `?a=1&b=2` all resolve rather than needing to match
 * character for character.
 */
window.addEventListener("message", m => {
    let update
    try {
        update = JSON.parse(m.data)
    } catch (error) {
        // Not ours. Anything on the page may postMessage, and most of it is not JSON.
        return
    }

    if (!update || update.type !== "ez-frame-resize") return

    const frames = findFrames(update.url)
    if (frames.length === 0) {
        // Loud on purpose. The bug this replaced was invisible for as long as it existed because
        // the failure was swallowed; a resize that cannot find its iframe should say so.
        console.warn("Ez frame resizer - no iframe matches " + update.url)
        return
    }

    frames.forEach(frame => frame.setAttribute("height", update.height + "px"))
});

/** Every iframe showing the document at `url`. */
function findFrames(url) {
    const target = parse(url)
    if (!target) return []
    // All of them, not the first: the same exercise embedded twice on a page is one URL and two
    // iframes, and only one of them used to be sized. `frame.src` is the IDL property, already
    // resolved against the document's base URI, so a relative `src` needs no special case.
    return Array.prototype.slice.call(document.querySelectorAll("iframe"))
        .filter(frame => parse(frame.src) === target)
}

/** A URL in one canonical spelling, or undefined if it is not a URL at all. */
function parse(url) {
    try {
        return new URL(url).href
    } catch (error) {
        return undefined
    }
}
