package core.ems.service.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Files on the local disk, one per key, flat in a single directory.
 *
 * This exists so that a laptop and CI can run the whole feature — upload, serve, sweep — with no AWS
 * account, no credentials and no network. It is the default backend, and it is what
 * `doc/core/files-check.sh` and `./gradlew :core:bootRun` use.
 *
 * It is deliberately not a general-purpose store: no directory sharding, no locking, no concurrent
 * writer story beyond an atomic rename. At the scale a development database reaches, none of that
 * earns its keep, and pretending otherwise would invite someone to deploy it.
 */
@Service
@ConditionalOnProperty(name = ["easy.core.storage.backend"], havingValue = "local", matchIfMissing = true)
class LocalFsStorageService : StorageService {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.storage.local.dir}")
    private lateinit var dirName: String

    private val dir: Path get() = Path.of(dirName)

    @PostConstruct
    fun init() {
        Files.createDirectories(dir)
        log.info { "Storing uploaded files on the local filesystem, in $dir" }
    }

    override fun put(key: String, bytes: InputStream, sizeBytes: Long, mimeType: String, contentDisposition: String) {
        // Neither header is stored: on this backend the read endpoint streams the bytes itself and
        // derives both from the database row, so there is nowhere for them to be kept. The S3
        // backend has to attach them to the object because the browser talks to S3 directly.
        val target = resolve(key)
        val tmp = Files.createTempFile(dir, "upload-", ".part")
        try {
            bytes.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    override fun get(key: String): InputStream? {
        val file = resolve(key)
        return if (file.exists() && file.isRegularFile()) Files.newInputStream(file) else null
    }

    override fun delete(keys: Collection<String>) {
        // Deliberately not requiring the key shape. The sweep's orphan pass deletes whatever the
        // listing returned, and a directory can hold something that is not one of our keys — an
        // object written by an older format, or a file someone put there by hand. Refusing to delete
        // it is what the first version did, and the effect was that one unparseable name aborted the
        // whole nightly run, forever, after the rows had already gone.
        keys.forEach { Files.deleteIfExists(resolveWithinDir(it)) }
    }

    override fun listKeys(): Set<String> {
        if (!dir.exists()) return emptySet()
        return Files.list(dir).use { paths ->
            paths.filter { it.isRegularFile() }
                .map { it.name }
                // Partial uploads that a crash left behind. They are not keys and must never be
                // reported as orphaned objects, or the sweep would spend every night deleting the
                // debris of the last one.
                .filter { !it.startsWith("upload-") }
                .toList()
                .toSet()
        }
    }

    /** No web server serves this directory, so the read endpoint streams the bytes instead. */
    override fun publicUrl(key: String) = null

    /**
     * For keys that came from outside — a URL path segment, so attacker-controlled until proven
     * otherwise. [assertValidStorageKey] rejects anything that is not the exact alphabet and length
     * [newStorageKey] produces, which rules out `..` and `/` before a path is ever built.
     */
    private fun resolve(key: String): Path {
        assertValidStorageKey(key)
        return resolveWithinDir(key)
    }

    /**
     * Containment only, for names that came from the storage directory itself and therefore may not
     * be keys at all. Still checked rather than trusted: a listing is only as trustworthy as the
     * directory, and this costs nothing.
     */
    private fun resolveWithinDir(name: String): Path {
        val resolved = dir.resolve(name).normalize()
        check(resolved.parent == dir.normalize()) { "Storage name '$name' escaped the storage directory" }
        return resolved
    }
}
