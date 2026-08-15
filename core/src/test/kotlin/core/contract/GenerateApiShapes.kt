package core.contract

import core.testing.DtoIntrospection
import core.testing.Endpoint
import core.testing.EndpointInventory
import core.testing.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.jvm.kotlinFunction

/**
 * The shape of every response and request body core puts on the wire, written to
 * **`doc/core/api-shapes.json`** and pinned there.
 *
 * ### The problem this solves
 *
 * Every browser test in `web/dev-harness` stubs the backend from fixtures we wrote by hand, and
 * nothing checks those fixtures still resemble what core returns. The failure mode is a **green
 * suite and a broken app**, and it has already happened once: a fixture kept
 * `anonymous_autoassess_template: null` after the column became non-nullable, and neither side
 * noticed, because both sides were mocked.
 *
 * ### Why a committed file rather than generated OpenAPI
 *
 * Not size — this is a couple of hundred lines of reflection either way. It is that **the artefact
 * is in git, so the diff is the review**. When a column becomes non-nullable, the pull request shows
 *
 * ```
 * -      "anonymous_autoassess_template": { "kind": "string", "nullable": true },
 * +      "anonymous_autoassess_template": { "kind": "string" },
 * ```
 *
 * on one line, and a human catches it there — before any fixture is touched, and without anything
 * needing to fail. A generated document consumed by a tool gets that value only if the tool is
 * running; a diff gets it for free.
 *
 * springdoc was the alternative and was rejected because core is on Spring Boot 4.1 with Jackson 3,
 * ahead of its supported matrix, and because its output is a document for a tool rather than a diff
 * for a person. **Not** because it needs a booted Spring context: EZ-1770 argued that, and this test
 * is `@IntegrationTest`, so it boots the whole context and a Testcontainers PostgreSQL just like
 * every other test here. Reading the resolved patterns out of `RequestMappingHandlerMapping` was
 * worth the context — it is the same inventory the authorization matrix uses, so the keys in this
 * file and the endpoints there cannot drift apart.
 *
 * Recording real responses was rejected for a sharper reason — a field that is null in every
 * recording is indistinguishable from a nullable field, which is precisely the bug above.
 *
 * ### What it records, and what it does not
 *
 * Wire name, kind, nullability, and enum values. Nullability is the one that matters most and is the
 * one only *this* side knows: `KType.isMarkedNullable` is ground truth, and no amount of sampling
 * real responses recovers it.
 *
 * It says nothing about behaviour — a 403 where 200 was expected, an empty list where items were
 * expected, a semantic change with an identical shape. That stays with `doc/core/api-testing.md`
 * and the `*-check.sh` scripts.
 *
 * ### Regenerating
 *
 * ```
 * ./gradlew :core:test --tests '*GenerateApiShapes*' -Pcontract.write=true
 * ```
 *
 * then read the diff before committing it. That is the whole point of the file.
 *
 * Needs Docker running, like every `@IntegrationTest` here — without it the failure is a container
 * startup error, which says nothing about shapes and is a confusing way to be told to start Docker.
 */
@IntegrationTest
class GenerateApiShapes(@Autowired private val mapping: RequestMappingHandlerMapping) {

    private val outputFile = File("../doc/core/api-shapes.json")

    // --- the model ---------------------------------------------------------------------------

    private data class Field(val kind: String, val nullable: Boolean, val ref: String?, val values: List<String>?)

    private val types = sortedMapOf<String, Map<String, Field>>()

    /**
     * Types are emitted once and referenced by name, rather than inlined at every use.
     *
     * A DTO used by five endpoints would otherwise appear five times, and a nullability change would
     * be a five-place diff — which is the sort of noise that gets skimmed. One definition means one
     * changed line.
     */
    private fun describe(type: KType): Field {
        val kClass = type.classifier as? KClass<*>
            ?: return Field("opaque", type.isMarkedNullable, null, null)
        val nullable = type.isMarkedNullable

        return when {
            kClass == String::class -> Field("string", nullable, null, null)
            kClass == Boolean::class -> Field("boolean", nullable, null, null)
            kClass == Int::class || kClass == Long::class ||
                    kClass == Double::class || kClass == Float::class -> Field("number", nullable, null, null)

            kClass.java.isEnum -> Field(
                "enum", nullable, null,
                kClass.java.enumConstants.map { (it as Enum<*>).name }.sorted()
            )

            kClass.qualifiedName == "org.joda.time.DateTime" -> Field("datetime", nullable, null, null)

            kClass == List::class || kClass == Set::class || kClass == Collection::class -> {
                val element = type.arguments.firstOrNull()?.type
                val ref = element?.let { registerIfOurs(it) }
                Field("array", nullable, ref, null)
            }

            DtoIntrospection.isOurs(kClass) -> Field("object", nullable, register(kClass), null)

            // Byte arrays, Map, Any, StreamingResponseBody, ResponseEntity bodies we cannot see
            // into. Recorded as opaque rather than guessed at — an honest gap, and the web side
            // skips validating them rather than inventing a rule.
            else -> Field("opaque", nullable, null, null)
        }
    }

    private fun registerIfOurs(type: KType): String? {
        val kClass = type.classifier as? KClass<*> ?: return null
        return if (DtoIntrospection.isOurs(kClass) && !kClass.java.isEnum) register(kClass) else null
    }

    /** The registered name for a type that is one of ours directly (not wrapped in a collection). */
    private fun registeredName(type: KType): String? {
        val kClass = type.classifier as? KClass<*> ?: return null
        return if (DtoIntrospection.isOurs(kClass) && !kClass.java.isEnum) register(kClass) else null
    }

    private fun register(kClass: KClass<*>): String {
        val name = kClass.qualifiedName ?: kClass.java.name
        if (types.containsKey(name)) return name
        types[name] = emptyMap() // guard against recursive types before walking

        types[name] = DtoIntrospection.properties(kClass)
            .associate { it.wireName to describe(it.type) }
            .toSortedMap()
        return name
    }

    // --- rendering ---------------------------------------------------------------------------

    private fun renderField(f: Field): String = buildString {
        append("{ \"kind\": \"${f.kind}\"")
        if (f.nullable) append(", \"nullable\": true")
        f.ref?.let { append(", \"type\": \"$it\"") }
        f.values?.let { append(", \"values\": [${it.joinToString(", ") { v -> "\"$v\"" }}]") }
        append(" }")
    }

    private fun render(endpoints: Map<String, Pair<String?, String?>>): String = buildString {
        appendLine("{")
        appendLine("  \"_\": \"Generated by core/src/test/kotlin/core/contract/GenerateApiShapes.kt — do not edit by hand.\",")
        appendLine("  \"_regenerate\": \"./gradlew :core:test --tests '*GenerateApiShapes*' -Pcontract.write=true\",")

        appendLine("  \"endpoints\": {")
        val endpointLines = endpoints.toSortedMap().map { (key, refs) ->
            val (request, response) = refs
            val parts = listOfNotNull(
                request?.let { "\"request\": \"$it\"" },
                response?.let { "\"response\": \"$it\"" },
            )
            "    \"$key\": { ${parts.joinToString(", ")} }"
        }
        appendLine(endpointLines.joinToString(",\n"))
        appendLine("  },")

        appendLine("  \"types\": {")
        val typeLines = types.map { (name, fields) ->
            val body = fields.entries.joinToString(",\n") { (wire, f) ->
                "      \"$wire\": ${renderField(f)}"
            }
            if (fields.isEmpty()) "    \"$name\": {}" else "    \"$name\": {\n$body\n    }"
        }
        appendLine(typeLines.joinToString(",\n"))
        appendLine("  }")
        append("}")
    }

    // --- the test ----------------------------------------------------------------------------

    private fun responseType(e: Endpoint): KType? {
        val fn = runCatching { e.handler.method.kotlinFunction }.getOrNull() ?: return null
        var type = fn.returnType
        // ResponseEntity<T> is a transport wrapper; the wire sees T.
        if ((type.classifier as? KClass<*>)?.qualifiedName == "org.springframework.http.ResponseEntity") {
            type = type.arguments.firstOrNull()?.type ?: return null
        }
        if ((type.classifier as? KClass<*>) == Unit::class) return null
        return type
    }

    private fun requestType(e: Endpoint): KType? {
        val fn = runCatching { e.handler.method.kotlinFunction }.getOrNull() ?: return null
        return fn.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .firstOrNull { p -> p.annotations.any { it is RequestBody } }
            ?.type
    }

    @Test
    fun `the committed API shapes match the code`() {
        val endpoints = EndpointInventory.all(mapping)
            .filter { DtoIntrospection.isOurs(it.handler.beanType.kotlin) }

        assertTrue(endpoints.size >= 100) {
            "Only ${endpoints.size} of our endpoints found — the scan is broken, and this test would " +
                    "then pin an almost-empty document while claiming the contract is covered."
        }

        // Routed through describe(), not `takeIf { isOurs }` on the classifier. The latter recorded
        // nothing at all for a handler returning a bare `List<OurResp>` — the classifier is `List`,
        // which is not ours — so three real endpoints (`GET /v2/executors`,
        // `GET /v2/container-images`, `GET /v2/courses/teacher/{courseId}/grades`) appeared as `{}`,
        // indistinguishable from the DELETEs that genuinely return Unit. Nothing was broken by it
        // today only because no script stubs them; the first one to try would have got silent
        // non-coverage. describe() already unwraps collections.
        fun shapeRef(t: KType?): String? = t?.let { describe(it).ref ?: registeredName(it) }

        val described = endpoints.associate { e ->
            "${e.method} ${e.pattern}" to (shapeRef(requestType(e)) to shapeRef(responseType(e)))
        }

        val generated = render(described)

        if (System.getProperty("contract.write") == "true") {
            // Never create the directory. This path is relative to the test JVM's working directory,
            // which Gradle sets to `core/` — but a plain JUnit run configuration need not, and
            // `mkdirs()` would then happily invent a doc/core tree somewhere else and report success.
            // The same trap was caught by review in the changelog baseline; this is the cheaper half
            // of the fix, since doc/ cannot be read from the classpath.
            assertTrue(outputFile.parentFile.isDirectory) {
                "${outputFile.parentFile.canonicalPath} is not a directory — this test expects the " +
                        "working directory to be the core module. Run it through Gradle."
            }
            outputFile.writeText(generated + "\n")
            println("Wrote ${outputFile.canonicalPath}. Read the diff before committing it.")
            return
        }

        assertTrue(outputFile.isFile) {
            "${outputFile.canonicalPath} does not exist. Generate it with:\n" +
                    "  ./gradlew :core:test --tests '*GenerateApiShapes*' -Pcontract.write=true\n" +
                    "then review and commit it."
        }

        val committed = outputFile.readText().trim()
        assertTrue(committed == generated.trim()) {
            "doc/core/api-shapes.json no longer matches the code:\n\n" +
                    diff(committed.lines(), generated.trim().lines()) +
                    "\n\nThis is expected whenever a DTO changes, and the diff above is the point — it is " +
                    "how a field becoming non-nullable, or being renamed on the wire, gets seen by a " +
                    "human before the web fixtures drift away from it.\n\n" +
                    "Regenerate with:\n" +
                    "  ./gradlew :core:test --tests '*GenerateApiShapes*' -Pcontract.write=true\n\n" +
                    "then READ the diff. A changed `nullable`, a changed wire name, or a removed field " +
                    "is a breaking change for web/src/api/types.ts and the dev-harness fixtures."
        }
    }

    /**
     * Only the lines that differ.
     *
     * `assertEquals` on the whole document was the first attempt, and it printed 1148 lines twice —
     * so the one thing this test exists to show, the *change*, was the one thing you could not see.
     *
     * **Multiset difference, not set difference.** Set difference was the second attempt and it
     * silently swallowed half the answer: making `moodle_short_name` non-nullable on one DTO printed
     * only the `+` line, because the identical non-nullable line also exists on a *different* DTO and
     * the subtraction cancelled it. Comparing counts keeps both sides.
     *
     * No LCS: the document is sorted throughout, so lines never move, and added-or-removed is the
     * whole vocabulary.
     */
    private fun diff(committed: List<String>, generated: List<String>): String {
        fun counts(lines: List<String>) = lines.groupingBy { it }.eachCount()
        val before = counts(committed)
        val after = counts(generated)

        fun surplus(a: Map<String, Int>, b: Map<String, Int>) = a.entries
            .flatMap { (line, n) -> List(maxOf(0, n - (b[line] ?: 0))) { line } }
            .map { it.trim() }
            .sorted()

        val removed = surplus(before, after)
        val added = surplus(after, before)
        val shown = 40

        return buildString {
            removed.take(shown).forEach { appendLine("  - $it") }
            if (removed.size > shown) appendLine("  … and ${removed.size - shown} more removed")
            added.take(shown).forEach { appendLine("  + $it") }
            if (added.size > shown) appendLine("  … and ${added.size - shown} more added")
        }.trimEnd()
    }
}
