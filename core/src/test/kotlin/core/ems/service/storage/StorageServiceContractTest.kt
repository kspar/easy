package core.ems.service.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MinIOContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files

/**
 * Both storage backends, against the same assertions.
 *
 * `doc/core/files-check.sh` ran against whichever backend the core it was pointed at happened to
 * use. That is the property this file keeps, and the reason it matters is blunt: **production runs
 * S3 and CI runs local**, so any behaviour the two do not share is behaviour nothing checks until a
 * teacher's image is missing.
 *
 * Every test here is a `@ParameterizedTest` over both implementations rather than a pair of test
 * classes, so a new assertion cannot accidentally cover one backend and not the other. The local
 * one writes into a fresh temp directory; the S3 one into a MinIO container.
 *
 * ### What this deliberately does not check
 *
 * **Whether an object is actually readable by an anonymous caller.** That is a bucket *policy*, set
 * out of band once per environment ([S3StorageService] contains no ACL call by design), so the only
 * honest place to ask it is against a deployed bucket — which is `doc/core/s3-check.sh`, and is why
 * that script stays a script. Asserting it against MinIO would answer a question about MinIO.
 *
 * [publicUrl] is therefore checked for its *shape* only. That is not nothing: it is what
 * `ReadStoredFileController` puts in a `Location` header, so a trailing-slash bug there is a broken
 * image on every article at once.
 */
class StorageServiceContractTest {

    companion object {

        /**
         * A MinIO, or null when there is no Docker **at all**.
         *
         * Two different situations, deliberately treated differently:
         *
         * - **No Docker daemon.** Skip, by name, with the reason. The suite has a documented
         *   no-Docker path (`EASY_TEST_JDBC_URL`), so this has to remain runnable — but a backend
         *   quietly dropping out of a "both backends" test would be exactly the vacuous pass this
         *   programme is about, so it is a skip and not a silent omission.
         * - **Docker present and the container will not start.** Fail. That is a broken environment
         *   or an image that has moved, and turning it into a skip would reduce the suite to the
         *   local backend on the machine that most needs telling.
         */
        private val minio: MinIOContainer? by lazy {
            if (!runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)) null
            // Pinned, and not to just any release. The AWS SDK v2 this project uses sends
            // `x-amz-checksum-*` on DeleteObjects rather than the older `Content-MD5`; MinIO
            // releases before roughly 2025 reject that with "Missing required header for this
            // request: Content-Md5", which fails only the delete tests and looks like our bug.
            // Real S3 accepts both. If this tag ever needs moving, move it forward.
            else MinIOContainer("minio/minio:RELEASE.2025-04-22T22-12-26Z").also { it.start() }
        }

        private const val BUCKET = "easy-contract-test"

        @JvmStatic
        fun backends(): List<Array<Any?>> = listOf(
            arrayOf<Any?>("local", localBackend()),
            arrayOf<Any?>("s3", s3Backend()),
        )

        private fun localBackend(): StorageService = LocalFsStorageService().apply {
            setPrivateField("dirName", Files.createTempDirectory("storage-contract-").toAbsolutePath().toString())
            init()
        }

        private fun s3Backend(): StorageService? {
            val container = minio ?: return null

            // The bucket has to exist before the service does — S3StorageService only ever puts and
            // gets, which is the right division: creating buckets is provisioning, and a service
            // that made its own would hide a misconfigured bucket name behind a working test.
            S3Client.builder()
                .region(Region.of("eu-north-1"))
                .endpointOverride(URI.create(container.s3URL))
                .forcePathStyle(true)
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(container.userName, container.password)
                    )
                )
                .build()
                .use { admin ->
                    runCatching { admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()) }
                }

            return S3StorageService().apply {
                setPrivateField("bucket", BUCKET)
                setPrivateField("region", "eu-north-1")
                setPrivateField("endpoint", container.s3URL)
                // **With a trailing slash, deliberately.** `publicUrl` does `trimEnd('/')` before
                // appending the key, and without a slash here the assertion that it does so is
                // unfalsifiable — deleting the trim leaves the test green. A configured
                // `public-base-url` ending in `/` is the obvious way for a human to write one, and
                // the double slash it would otherwise produce is a broken image on every article.
                setPrivateField("publicBaseUrl", "${container.s3URL}/$BUCKET/")
                setPrivateField("accessKey", container.userName)
                setPrivateField("secretKey", container.password)
                init()
            }
        }

        /**
         * Set a `@Value` field without a Spring context.
         *
         * Reflection, because both services declare their configuration as `private lateinit var`
         * and standing up a context to fill six strings would mean a second Spring context (they are
         * `@ConditionalOnProperty` on mutually exclusive values) at ten seconds a fork.
         *
         * `getDeclaredField` throws if the field is renamed, and `lateinit` throws if one is missed,
         * so both ways of getting this wrong are loud. That is the whole reason this is acceptable.
         */
        private fun Any.setPrivateField(name: String, value: Any) {
            javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(this, value)
        }
    }

    private fun backendOrSkip(backend: StorageService?, name: String): StorageService {
        assumeTrue(backend != null) { "No Docker, so the '$name' backend could not be started" }
        return backend!!
    }

    private fun put(storage: StorageService, key: String, content: ByteArray, mimeType: String = "image/png") =
        storage.put(key, ByteArrayInputStream(content), content.size.toLong(), mimeType, "inline; filename=\"x.png\"")

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("what goes in comes out, byte for byte")
    fun roundTrip(name: String, backend: StorageService?) {
        val storage = backendOrSkip(backend, name)
        val key = newStorageKey()
        // Deliberately not text: an encoding bug in a store is invisible against ASCII and total
        // against a PNG, and a PNG is what this actually holds.
        val content = ByteArray(4096) { (it % 251).toByte() }

        put(storage, key, content)

        val read = storage.get(key)!!.use { it.readBytes() }
        assertEquals(content.toList(), read.toList())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("an absent key reads as null rather than throwing")
    fun absentIsNull(name: String, backend: StorageService?) {
        // The read endpoint distinguishes "no row" from "row but no object" and logs a warning for
        // the second. A backend that threw instead would turn a missing image into a 500 and an
        // admin e-mail, per file, per page load.
        assertNull(backendOrSkip(backend, name).get(newStorageKey()))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("deleting is idempotent, and deleting nothing is not an error")
    fun deleteIsIdempotent(name: String, backend: StorageService?) {
        val storage = backendOrSkip(backend, name)
        val key = newStorageKey()
        put(storage, key, byteArrayOf(1, 2, 3))

        storage.delete(listOf(key))
        assertNull(storage.get(key))

        // The sweep deletes rows first and objects second, so it re-attempts keys that are already
        // gone on its next run. A backend that threw on the second attempt would abort the sweep in
        // the same place every night, forever, with the rows already deleted.
        storage.delete(listOf(key))
        storage.delete(emptyList())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("listKeys is what the sweep sees, and it sees exactly what was stored")
    fun listKeys(name: String, backend: StorageService?) {
        val storage = backendOrSkip(backend, name)
        val keys = List(3) { newStorageKey() }
        keys.forEach { put(storage, it, byteArrayOf(7)) }

        val listed = storage.listKeys()
        assertTrue(listed.containsAll(keys)) { "Stored $keys, listed $listed" }

        // The orphan pass deletes everything listed that has no row. A key that vanishes from the
        // listing is a permanent leak; a key that appears and should not is a deleted live file.
        storage.delete(listOf(keys[0]))
        assertFalse(storage.listKeys().contains(keys[0]))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("a key that is not a key is refused before a path or a request is built")
    fun rejectsMalformedKeys(name: String, backend: StorageService?) {
        val storage = backendOrSkip(backend, name)

        // These arrive as a URL path segment on an unauthenticated endpoint, so they are attacker
        // controlled. `..` and `/` are gone before anything concatenates them into a path.
        listOf("../../etc/passwd", "short", "a".repeat(28), "", "with/slash", "with space${"x".repeat(17)}")
            .forEach { bad ->
                assertThrows(IllegalArgumentException::class.java, { storage.get(bad) }, "get('$bad') was allowed")
                assertThrows(
                    IllegalArgumentException::class.java,
                    { put(storage, bad, byteArrayOf(1)) },
                    "put('$bad') was allowed",
                )
            }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    @DisplayName("delete does not require the key shape, because the sweep hands it whatever it listed")
    fun deleteAcceptsNonKeys(name: String, backend: StorageService?) {
        // The asymmetry with the test above is deliberate and is written up in both backends: a
        // storage directory or a bucket can hold something that is not one of our keys — an older
        // format, a file put there by hand — and refusing to delete it is what made the first
        // version of the sweep abort forever on one unparseable name.
        backendOrSkip(backend, name).delete(listOf("not-a-key", "legacy_file.png", "UPPER.and.dots"))
    }

    /**
     * And the one thing `delete` does still refuse on the local backend: a name that leaves the
     * directory.
     *
     * **The two backends genuinely differ here**, which is the kind of thing a "both backends" test
     * exists to make visible rather than to paper over. S3 has no such concept — a key with slashes
     * in it is an ordinary key — so this is asserted for local only, and the parameterised test
     * above was narrowed to names both accept after it caught the difference.
     *
     * It is unreachable through the sweep as written: `listKeys` returns `Path.name`, the last
     * segment, and the row ids it is combined with were validated at insert. So this is containment
     * held in reserve — and the reason to pin it is precisely that the argument for why it cannot
     * fire lives in a different file from the check itself.
     *
     * The consequence if it ever did fire: `delete` iterates, so the throw abandons the rest of the
     * batch. The sweep survives that — it catches, logs, and the orphan pass re-lists them tomorrow
     * — which is the whole argument for deleting rows before objects.
     */
    @Test
    @DisplayName("the local backend still refuses a name that escapes its directory")
    fun localDeleteRefusesEscape() {
        val storage = backendOrSkip(localBackend(), "local")
        assertThrows(IllegalStateException::class.java) { storage.delete(listOf("../outside.png")) }
        assertThrows(IllegalStateException::class.java) { storage.delete(listOf("nested/inside.png")) }
    }

    @Test
    @DisplayName("the local backend has no public url, so the read endpoint streams")
    fun localHasNoPublicUrl() {
        assertNull(backendOrSkip(localBackend(), "local").publicUrl(newStorageKey()))
    }

    @Test
    @DisplayName("the s3 backend's public url is the base and the key, with exactly one slash")
    fun s3PublicUrlShape() {
        val storage = backendOrSkip(s3Backend(), "s3")
        val key = newStorageKey()
        val url = storage.publicUrl(key)!!

        assertTrue(url.endsWith("/$key")) { url }
        assertFalse(url.contains("//$key")) { "A trailing slash on public-base-url doubled up: $url" }
        assertNotNull(URI.create(url).host) { "Not an absolute URL: $url" }
    }
}
