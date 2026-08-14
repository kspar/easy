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
     * [mimeType] and [filename] become response headers on the stored object, so a browser that
     * follows the redirect sees a real content type and a human filename rather than the key.
     */
    fun put(key: String, bytes: InputStream, sizeBytes: Long, mimeType: String, filename: String)

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
