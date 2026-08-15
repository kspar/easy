package core.ems.service.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.net.URI

/**
 * Uploaded files in an S3 bucket whose objects are **publicly readable**.
 *
 * That is the decision on EZ-1571 and it is worth restating where the code is, because nothing here
 * looks like it is making it: there is no ACL call and no policy — the bucket is configured that
 * way once, out of band, and this class simply puts objects into it.
 *
 * What it accepts: an object URL is a bearer token that never expires and cannot be revoked. A file
 * attached to unreleased content is effectively public from the moment it is uploaded, because the
 * URL leaks through crawlers, logs, embeds and browser history whatever we do. The protection is
 * that keys are unguessable ([newStorageKey]) and that the bucket **must deny `ListBucket` to
 * anonymous callers** — public objects, not a public listing. If keys can be enumerated the whole
 * scheme collapses, and that is the easiest part of the setup to get wrong.
 *
 * The exit, if unreleased-content exposure ever becomes a real problem: this class starts returning
 * null from [publicUrl] and the read endpoint issues short-lived pre-signed URLs after a permission
 * check. No stored article is touched, because no stored article contains a bucket URL — that is
 * the entire reason `/v2/resource/...` exists.
 */
@Service
@ConditionalOnProperty(name = ["easy.core.storage.backend"], havingValue = "s3")
class S3StorageService : StorageService {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.storage.s3.bucket}")
    private lateinit var bucket: String

    @Value("\${easy.core.storage.s3.region}")
    private lateinit var region: String

    /** Empty for real AWS. Set it to run against MinIO or another S3-compatible endpoint. */
    @Value("\${easy.core.storage.s3.endpoint}")
    private lateinit var endpoint: String

    /** What an object's URL is prefixed with, e.g. `https://<bucket>.s3.<region>.amazonaws.com`. */
    @Value("\${easy.core.storage.s3.public-base-url}")
    private lateinit var publicBaseUrl: String

    /**
     * Optional. Empty means the SDK's default chain — environment, profile, or an instance role,
     * which is the better answer anywhere that can have one. These exist for hosts that cannot, and
     * they live in the secrets file the deployment config imports, never in the managed config.
     */
    @Value("\${easy.core.storage.s3.access-key:}")
    private lateinit var accessKey: String

    @Value("\${easy.core.storage.s3.secret-key:}")
    private lateinit var secretKey: String

    private lateinit var client: S3Client

    @PostConstruct
    fun init() {
        check(bucket.isNotBlank()) { "easy.core.storage.s3.bucket is empty, but the storage backend is s3" }
        check(publicBaseUrl.isNotBlank()) {
            "easy.core.storage.s3.public-base-url is empty, but the storage backend is s3. It is what " +
                    "/v2/resource redirects to; without it every image would 404."
        }

        client = S3Client.builder()
            .region(Region.of(region))
            .apply {
                if (endpoint.isNotBlank()) {
                    endpointOverride(URI.create(endpoint))
                    // MinIO and most other S3-compatible servers do not do virtual-host addressing.
                    forcePathStyle(true)
                }
                if (accessKey.isNotBlank()) {
                    credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                    )
                } else {
                    credentialsProvider(DefaultCredentialsProvider.create())
                }
            }
            .build()

        log.info {
            "Storing uploaded files in S3 bucket '$bucket' (region $region" +
                    (if (endpoint.isNotBlank()) ", endpoint $endpoint" else "") + "), served from $publicBaseUrl"
        }
    }

    override fun put(key: String, bytes: InputStream, sizeBytes: Long, mimeType: String, contentDisposition: String) {
        assertValidStorageKey(key)
        client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(mimeType)
                // The browser reaches the object directly after the redirect, so these headers are
                // fixed at upload time and cannot be reconsidered later without rewriting the object.
                // Quotes and control characters are already out — see sanitiseFilename.
                .contentDisposition(contentDisposition)
                .build(),
            // fromInputStream with a known length streams the request body. Handing it a byte array
            // instead would put the whole file in heap, which is exactly what the move away from
            // base64-in-JSON was for.
            RequestBody.fromInputStream(bytes, sizeBytes)
        )
    }

    override fun get(key: String): InputStream? {
        assertValidStorageKey(key)
        return try {
            client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
        } catch (e: NoSuchKeyException) {
            log.debug(e) { "No object for key $key" }
            null
        }
    }

    override fun delete(keys: Collection<String>) {
        // No key-shape check here on purpose — see LocalFsStorageService.delete. The sweep's orphan
        // pass deletes what the bucket listing returned, which need not be one of our keys.
        if (keys.isEmpty()) return
        // DeleteObjects takes at most 1000 keys per call.
        keys.chunked(1000).forEach { chunk ->
            client.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(
                        Delete.builder()
                            .objects(chunk.map { ObjectIdentifier.builder().key(it).build() })
                            .build()
                    )
                    .build()
            )
        }
    }

    override fun listKeys(): Set<String> {
        // Our own credentials, not an anonymous caller's: denying anonymous ListBucket is what keeps
        // keys unguessable, and it says nothing about what the application itself may do.
        val keys = mutableSetOf<String>()
        try {
            client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).build())
                .forEach { page -> page.contents().forEach { keys.add(it.key()) } }
        } catch (e: SdkException) {
            // The sweep asks for this to find objects with no row. Failing to list is a reason to
            // skip that pass, not to abandon the run — the reference scan is the half that matters.
            log.error(e) { "Could not list bucket '$bucket'; skipping the orphaned-object pass" }
            throw e
        }
        return keys
    }

    override fun publicUrl(key: String) = "${publicBaseUrl.trimEnd('/')}/$key"
}
