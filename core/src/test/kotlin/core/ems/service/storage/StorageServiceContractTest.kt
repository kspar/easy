package core.ems.service.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

/**
 * What any [StorageService] must do: put, get, list, delete, and refuse a key that is not one.
 *
 * **Still a contract test with one implementation.** It ran against two until EZ-1907 removed the
 * S3 backend, and the assertions here are deliberately written against the interface rather than
 * against [LocalFsStorageService] — they are the checklist a second backend would have to pass, and
 * the cheapest moment to have written them is before there is a second backend to argue with.
 *
 * Two things left with the S3 half and are worth recording as gone rather than forgotten:
 *
 * - **The MinIO container.** It pinned `minio/minio`, which was withdrawn from Docker Hub and took
 *   master's backend job red with it (EZ-1906). Nothing here needs Docker now, which is a property
 *   to keep: the suite's documented no-Docker path stays honest when the storage tests cannot
 *   quietly drop out of it.
 * - **`publicUrl` and its shape assertions.** The read endpoint has one branch now, so there is no
 *   redirect to get wrong. `FileApiTest` covers what it serves.
 *
 * ### What this deliberately does not check
 *
 * Durability. Nothing replicates the upload directory, so the file that matters is the host's
 * backup job, and a test that asserted otherwise against a temp directory would be asserting a
 * comfort rather than a fact.
 */
class StorageServiceContractTest {

    private fun backend(): StorageService = LocalFsStorageService().apply {
        // Reflection, because the service declares its directory as a `private lateinit var` filled
        // by `@Value`, and standing up a Spring context to fill one string costs ten seconds a fork.
        // `getDeclaredField` throws if the field is renamed and `lateinit` throws if it is missed,
        // so both ways of getting this wrong are loud. That is the whole reason this is acceptable.
        javaClass.getDeclaredField("dirName").also { it.isAccessible = true }
            .set(this, Files.createTempDirectory("storage-contract-").toAbsolutePath().toString())
        init()
    }

    private fun put(storage: StorageService, key: String, content: ByteArray) =
        storage.put(key, ByteArrayInputStream(content))

    @Test
    @DisplayName("what goes in comes out, byte for byte")
    fun roundTrip() {
        val storage = backend()
        val key = newStorageKey()
        // Deliberately not text: an encoding bug in a store is invisible against ASCII and total
        // against a PNG, and a PNG is what this actually holds.
        val content = ByteArray(4096) { (it % 251).toByte() }

        put(storage, key, content)

        val read = storage.get(key)!!.use { it.readBytes() }
        assertEquals(content.toList(), read.toList())
    }

    @Test
    @DisplayName("an absent key reads as null rather than throwing")
    fun absentIsNull() {
        // The read endpoint distinguishes "no row" from "row but no object" and logs a warning for
        // the second. A backend that threw instead would turn a missing image into a 500 and an
        // admin e-mail, per file, per page load.
        assertNull(backend().get(newStorageKey()))
    }

    @Test
    @DisplayName("deleting is idempotent, and deleting nothing is not an error")
    fun deleteIsIdempotent() {
        val storage = backend()
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

    @Test
    @DisplayName("listKeys is what the sweep sees, and it sees exactly what was stored")
    fun listKeys() {
        val storage = backend()
        val keys = List(3) { newStorageKey() }
        keys.forEach { put(storage, it, byteArrayOf(7)) }

        val listed = storage.listKeys()
        assertTrue(listed.containsAll(keys)) { "Stored $keys, listed $listed" }

        // The orphan pass deletes everything listed that has no row. A key that vanishes from the
        // listing is a permanent leak; a key that appears and should not is a deleted live file.
        storage.delete(listOf(keys[0]))
        assertFalse(storage.listKeys().contains(keys[0]))
    }

    @Test
    @DisplayName("a partial upload is never listed as an object the sweep could collect")
    fun partialUploadsAreNotKeys() {
        // A crash mid-`put` leaves an `upload-*.part` behind. It is not a key, it has no row, and a
        // listing that reported it would have the sweep deleting the debris of the last crash every
        // night — which is harmless right up until the filter is what somebody removes as dead code.
        val storage = backend()
        val key = newStorageKey()
        put(storage, key, byteArrayOf(1))

        val dir = LocalFsStorageService::class.java.getDeclaredField("dirName")
            .also { it.isAccessible = true }.get(storage) as String
        Files.createTempFile(java.nio.file.Path.of(dir), "upload-", ".part")

        assertEquals(setOf(key), storage.listKeys())
    }

    @Test
    @DisplayName("a key that is not a key is refused before a path is built")
    fun rejectsMalformedKeys() {
        val storage = backend()

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

    @Test
    @DisplayName("delete does not require the key shape, because the sweep hands it whatever it listed")
    fun deleteAcceptsNonKeys() {
        // The asymmetry with the test above is deliberate and is written up in the backend: a
        // storage directory can hold something that is not one of our keys — an older format, a file
        // put there by hand — and refusing to delete it is what made the first version of the sweep
        // abort forever on one unparseable name.
        backend().delete(listOf("not-a-key", "legacy_file.png", "UPPER.and.dots"))
    }

    /**
     * And the one thing `delete` does still refuse: a name that leaves the directory.
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
    @DisplayName("delete refuses a name that escapes the storage directory")
    fun deleteRefusesEscape() {
        val storage = backend()
        assertThrows(IllegalStateException::class.java) { storage.delete(listOf("../outside.png")) }
        assertThrows(IllegalStateException::class.java) { storage.delete(listOf("nested/inside.png")) }
    }
}
