package core.ems.service.storage

import java.io.InputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Where the bytes of an uploaded file live. [core.db.StoredFile] keeps only the metadata.
 *
 * There are two implementations and the choice is `easy.core.storage.backend`:
 * [S3StorageService] for anything deployed, [LocalFsStorageService] for a laptop and for CI, which
 * have no AWS account and are expected to work with no network at all.
 *
 * The URL a file is served from is *not* part of this interface by design. Content stores
 * `/v2/resource/<key>/<filename>` on our own origin permanently, and
 * [core.ems.service.file.ReadStoredFileController] decides per backend what to do with that request
 * — redirect to a public object, or stream. That indirection is the whole reason a storage change
 * never has to rewrite a stored article.
 */
interface StorageService {

    /**
     * Store [bytes] under [key]. [sizeBytes] is required rather than derived because S3 needs the
     * content length up front to stream a request body instead of buffering it, which is the only
     * reason a large upload is possible at all.
     *
     * [mimeType] and [contentDisposition] become response headers on the stored object, so a browser
     * that follows the redirect sees a real content type and a human filename rather than the key.
     * The disposition arrives already formatted — see [contentDispositionFor] — because whether a
     * file may render in the browser is a policy decision, and a storage backend is the wrong place
     * for one.
     */
    fun put(key: String, bytes: InputStream, sizeBytes: Long, mimeType: String, contentDisposition: String)

    /** Read an object back. Null when it is not there. The caller closes the stream. */
    fun get(key: String): InputStream?

    /**
     * Remove objects. Only [core.ems.cron.StoredFileSweep] calls this — see the "one deleter" note
     * there. Must be idempotent: deleting a key that is already gone is not an error.
     */
    fun delete(keys: Collection<String>)

    /**
     * Every key currently stored, for the sweep's orphan pass. Fine at the scale this runs at
     * (thousands); if it ever is not, the sweep is the thing to page rather than this signature.
     */
    fun listKeys(): Set<String>

    /**
     * The publicly fetchable URL of an object, or null when this backend has none — which is the
     * local one, where there is no web server in front of the directory. A null answer is what
     * makes the read endpoint stream instead of redirect.
     */
    fun publicUrl(key: String): String?
}


/**
 * A new storage key: 160 bits from a CSPRNG, base64url, 27 characters.
 *
 * **The key is the credential.** Objects are public and reads are unauthenticated, so anyone
 * holding this string can fetch the file forever — which is the decision recorded on EZ-1571, not
 * an oversight. What it demands is that the key be genuinely unguessable, and the id this replaces
 * was not: `SHA-256(base64 of the content + the upload millisecond)` truncated to 20 bytes is
 * *derived*, so its real entropy is bounded by how predictable those two inputs are. 160 random
 * bits cost nothing and remove the question.
 *
 * Base64url rather than hex for length: 27 characters against 40, in a string that ends up inside
 * every image URL in every article. The alphabet is `[A-Za-z0-9_-]`, which is also what
 * [core.ems.cron.STORED_FILE_URL_REGEX] matches when the sweep looks for references — change one
 * and the other stops finding files that are in use.
 */
fun newStorageKey(): String {
    val bytes = ByteArray(20)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Length of what [newStorageKey] produces: 20 bytes base64-encoded, unpadded. */
const val STORAGE_KEY_LENGTH = 27

private val STORAGE_KEY_PATTERN = Regex("[A-Za-z0-9_-]{$STORAGE_KEY_LENGTH}")

fun isValidStorageKey(key: String) = STORAGE_KEY_PATTERN.matches(key)

/**
 * Refuse anything that is not exactly what [newStorageKey] produces.
 *
 * A key reaches us as a URL path segment on an **unauthenticated** endpoint, so it is attacker
 * controlled. Checking the shape here rather than in each backend means `..`, `/` and a
 * thousand-character segment are all gone before anything builds a path or an S3 request out of it.
 */
fun assertValidStorageKey(key: String) =
    require(isValidStorageKey(key)) { "Not a storage key: '$key'" }


/**
 * Types a stored file may render in a browser as. Everything else downloads.
 *
 * **This used to be the other way round** — a two-element deny list, `text/html` and
 * `image/svg+xml`, with everything else served `inline`. Its reasoning was that an uploaded page
 * "cannot touch the application — different origin, no cookies, no session", and that is true of the
 * S3 backend, which redirects to a bucket URL. It is **false of the local backend**, which streams
 * through core: `roles/nginx` proxies `/v2/resource/` from the *web* origin in every environment, on
 * purpose, so a page served that way is same-origin with the SPA. `local` is the Spring default and
 * what production runs.
 *
 * And a deny list is the wrong shape for the question regardless. "Which types can a browser be
 * talked into executing script from" has no stable answer: `application/xhtml+xml` was missing and
 * renders natively with working `<script>`, and the next entry is whatever a browser starts rendering
 * next year. An allow list is wrong in the direction that shows up as a download instead of a
 * preview, which somebody reports.
 *
 * What is on it and why:
 *
 *  - **images, except SVG.** This is what the feature is for — `markdownForUpload` embeds an upload
 *    as an image if and only if its type starts `image/`, and links to it otherwise, so images are
 *    the only type the client ever renders on purpose. SVG is excluded for the reason the old comment
 *    gave and got right: safe inside `<img>`, scriptable when navigated to, and `Content-Disposition`
 *    does not affect `<img>`, so an SVG diagram keeps working while a link to it downloads.
 *  - **PDF, audio and video**, which have no DOM and are the types where a browser preview is worth
 *    something. A PDF can carry script, but for its own viewer, not for our origin.
 *
 * Deliberately *not* on it: `text/plain`. Tika sniffs a lot of things into `text/plain`, and whether
 * a browser re-sniffs it as HTML depends on `X-Content-Type-Options` surviving a proxy hop — which is
 * a thing to know rather than a thing to depend on. A `.txt` that downloads is a small price.
 *
 * The type is Tika's, sniffed from the content, so renaming a file does not move it between these
 * cases.
 */
private val MAY_RENDER_INLINE = setOf("application/pdf")
private val MAY_RENDER_INLINE_PREFIXES = listOf("image/", "audio/", "video/")
private val NEVER_INLINE = setOf("image/svg+xml")

/**
 * The `Content-Disposition` a stored file is served with. One function because it is applied in two
 * unrelated places — attached to the object at upload time for the S3 backend, and set on the
 * response at read time for the local one — and a policy that lives in two places is a policy that
 * will eventually disagree with itself.
 *
 * Note the asymmetry that follows from *where* each backend applies it: the local backend decides on
 * every read, so a change here covers files already stored, while S3 baked the answer into the object
 * when it was uploaded and keeps serving the old one. Production is local.
 */
fun contentDispositionFor(mimeType: String, filename: String): String {
    val kind = if (mayRenderInline(mimeType)) "inline" else "attachment"
    return """$kind; filename="$filename""""
}

fun mayRenderInline(mimeType: String): Boolean {
    val type = mimeType.substringBefore(';').trim().lowercase()
    if (type in NEVER_INLINE) return false
    return type in MAY_RENDER_INLINE || MAY_RENDER_INLINE_PREFIXES.any { type.startsWith(it) }
}
