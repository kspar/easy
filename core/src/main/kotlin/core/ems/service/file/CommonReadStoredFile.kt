package core.ems.service.file

import core.db.StoredFile
import core.ems.service.storage.StorageService
import core.ems.service.storage.contentDispositionFor
import core.ems.service.storage.isValidStorageKey
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletResponse
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Serving an uploaded file. **Unauthenticated, deliberately.**
 *
 * No `@Secured` and no `caller: EasyUser` parameter — the shape of `AnonymousReadArticle`, for a
 * related reason: a published article is readable by someone with no account, and an article with a
 * screenshot in it would otherwise 401 on the image for exactly the audience the article is public
 * for. Anonymous exercise embeds have the same problem. The path must also be listed in
 * `SecurityConf`'s permitAll matchers or the filter chain answers 401 before this is reached.
 *
 * There is no permission check and there never was one worth keeping: the endpoint this replaces was
 * `@Secured` to all three roles and then streamed whatever id it was handed, so any student could
 * fetch any file. The protection is that the key is unguessable, and the decision on EZ-1571 is to
 * make that the whole of it rather than a fig leaf over a check that does not exist.
 *
 * **The URL shape is permanent.** It goes into `text_html` when content is saved, and stored HTML is
 * the thing this design exists not to rewrite. `{filename}` is decoration — for the browser, and for
 * a human reading the URL — and is never looked up. A mismatch is not an error: rejecting one would
 * mean renaming a file breaks every article that links to it.
 *
 * The URL is **relative**, so the same stored content works in every environment — which is the
 * point, since content is rendered to HTML once and cached, and an absolute URL would bake one
 * environment's hostname into every article we ever write.
 *
 * That relies on the web origin proxying `/v2/resource/` to core, because web and API are separate
 * hostnames. `ansible/roles/nginx` does it. Without it an `<img>` gets whatever the web server
 * serves for an unknown path — a 404 page, or worse the SPA's `index.html` with a 200 on it, which
 * renders as a broken image and looks nothing like a proxy problem.
 */
@RestController
@RequestMapping("/v2")
class ReadStoredFileController(private val storageService: StorageService) {
    private val log = KotlinLogging.logger {}

    @GetMapping("/resource/{key}/{filename}")
    fun controller(
        @PathVariable("key") key: String,
        @PathVariable("filename") filename: String,
        response: HttpServletResponse
    ) {
        // Truncated on purpose. With no auth wall the key *is* the credential, so logging it in full
        // would turn the log into a key store readable by anyone with log access — and Apache/nginx
        // logs the path anyway, which is its own thing to think about.
        log.debug { "Serving file ${key.take(6)}… as '$filename'" }

        if (!isValidStorageKey(key)) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return
        }

        val file = selectFile(key)
        if (file == null) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return
        }

        // Content-addressed in the sense that matters: a key is minted once and its bytes never
        // change, so this can be cached for as long as a browser is willing to. If this ever becomes
        // a redirect to a *signed* URL, this header has to go with it — a year-long cache of a
        // ten-minute URL is a broken image for the rest of the year.
        response.setHeader("Cache-Control", "public, max-age=31536000, immutable")

        val publicUrl = storageService.publicUrl(key)
        if (publicUrl != null) {
            // The bucket URL is never stored anywhere; it exists only in this response. That is what
            // keeps the storage backend swappable without touching a single stored article.
            response.status = HttpServletResponse.SC_FOUND
            response.setHeader("Location", publicUrl)
            return
        }

        // Backends with no public URL — the local filesystem one — stream instead.
        val stream = storageService.get(key)
        if (stream == null) {
            log.warn { "File row ${key.take(6)}… exists but its object does not" }
            response.status = HttpServletResponse.SC_NOT_FOUND
            return
        }
        response.contentType = file.mimeType
        response.setHeader("Content-Length", file.sizeBytes.toString())
        // Same policy the S3 backend bakes into the object at upload time, applied here at read time
        // because this backend has no object metadata to bake it into.
        response.setHeader("Content-Disposition", contentDispositionFor(file.mimeType, file.filename))
        stream.use { it.copyTo(response.outputStream) }
    }

    private data class FileMeta(val mimeType: String, val filename: String, val sizeBytes: Long)

    private fun selectFile(key: String): FileMeta? = transaction {
        StoredFile.select(StoredFile.mimeType, StoredFile.filename, StoredFile.sizeBytes)
            .where { StoredFile.id eq key }
            .map { FileMeta(it[StoredFile.mimeType], it[StoredFile.filename], it[StoredFile.sizeBytes]) }
            .firstOrNull()
    }
}
