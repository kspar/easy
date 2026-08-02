/*
 * Parent-side half of the embed auto-resizer.
 *
 * An embedded exercise cannot size its own iframe, so the embedded page measures itself and posts
 * its height here; this listener finds the matching iframe and sets it. Carried over from wui
 * unchanged, at the same URL it was served from, because embeds already published in PmWiki and
 * elsewhere have this script tag baked into them — changing either the URL or the message format
 * would silently break every one of them, and they are on pages nobody here can edit.
 *
 * The page half lives in web/src/features/embed/EmbedExercisePage.tsx.
 */
window.addEventListener("message", m => {
    try {
        const update = JSON.parse(m.data)
        if (update.type !== "ez-frame-resize") {
            console.log("Ez frame resizer - got message with unknown type " + update.type)
            return
        }

        document.querySelector("iframe[src=\"" + normalizeForComparison(update.url) + "\"]")
            .setAttribute("height", update.height + "px")

    } catch (error) {
    }
});

function normalizeForComparison(url) {
    const [pathPart, queryPart] = url.split('?');
    return decodeURI(pathPart) + (queryPart ? '?' + queryPart : '');
}
