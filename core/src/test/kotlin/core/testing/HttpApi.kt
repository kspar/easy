package core.testing

import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The elements of a nested JSON array, e.g. `node.list("scripts")`.
 *
 * Use this, never `node.get("scripts").map { … }`. `JsonNode` carries its **own** `map`, which
 * Kotlin resolves in preference to the `Iterable` extension and which maps the node rather than its
 * elements — so every element comes back as the array itself, and the failure surfaces several lines
 * later as a coercion error or a null field. It has cost three separate debugging detours in this
 * suite; `HttpApi.Response.elements` is the same guard one level up.
 */
fun JsonNode.list(name: String): List<JsonNode> = get(name)?.toList().orEmpty()

/**
 * A nullable string field of a node, `null` for JSON null as well as for absent.
 *
 * The node-level twin of `HttpApi.Response.nullableField`, for reading fields off the elements of an
 * array. Same reason: `NullNode.asString()` is `""`.
 */
fun JsonNode.nullableText(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

/**
 * Calling the API the way a client does, for tests that are about an endpoint's *behaviour* rather
 * than about who may reach it.
 *
 * This is what `doc/core/articles-check.sh` and `files-check.sh` were, before they became
 * `ArticleApiTest` and `FileApiTest`. Those scripts were curl plus a python one-liner per assertion,
 * and the shape they settled on — status code, or the `code` field out of the error body, or one
 * field out of a success body — is what the three accessors below are. Keeping that shape makes the
 * ported assertions readable as the same assertions.
 *
 * ### Anonymous is a state, not an absence
 *
 * `authentication()` writes into the **static** `TestSecurityContextHolder`, which JUnit clears once
 * per test method rather than per request. So an anonymous call made after an authenticated one in
 * the same method silently inherits the previous caller — and a `401 for an anonymous request`
 * assertion then passes because the request was never anonymous. [anonymous] clears the context
 * explicitly, which is what makes "signed in, then not" safe to write in one test. The authorization
 * matrix hit this first and carries the same note.
 */
class HttpApi(private val mockMvc: MockMvc) {

    /**
     * A response, in the shapes an assertion here ever wants.
     *
     * Not a `data class`: it carries a `ByteArray`, whose generated `equals` would be identity, so
     * two responses with the same bytes would compare unequal and the failure message would say
     * nothing about why.
     */
    class Response(
        val status: Int,
        val body: String,
        val bytes: ByteArray,
        val headers: Map<String, List<String>>,
    ) {

        /** `null` when the body is not JSON — an empty 200, or a stack of HTML from the container. */
        val jsonOrNull: JsonNode? by lazy {
            runCatching { MAPPER.readTree(body) }.getOrNull()?.takeUnless { it.isMissingNode }
        }

        /**
         * `ReqError.errorCodeStr` out of an error body, e.g. `ENTITY_WITH_ID_NOT_FOUND`.
         *
         * The scripts asserted on this rather than on the HTTP status wherever core distinguishes
         * cases the status cannot: a draft article and a nonexistent one are both 400, and the
         * whole point of the first assertion in `ArticleApiTest` is that they are the *same* 400.
         */
        val errorCode: String? get() = jsonOrNull?.get("code")?.asString()

        /**
         * One field out of the body.
         *
         * **An error body has an `id` too** — `RequestErrorResponse.id` is the correlation id that
         * also goes in the log — so `field("id")` answers on a failed request just as readily as on
         * a successful one, with something that looks like an id and is not the entity's. Assert
         * [status] or [errorCode] first; do not read "an id came back" as "the thing was created".
         * This cost a green assertion in `ArticleApiTest` on its first run.
         */
        fun field(name: String): String? = jsonOrNull?.get(name)?.asString()

        /**
         * The field, or `null` when it is **JSON null or absent** — which [field] cannot tell apart
         * from an empty string.
         *
         * `NullNode.asString()` returns `""`, not null, so `assertNull(field("suggested_code"))`
         * fails with "expected: <null> but was: <>" on a field core serialised as `null`, and worse,
         * `assertEquals("", field(x))` *passes* on a null. Use this wherever a nullable field's
         * nullness is the assertion. Cost a run in `InlineCommentApiTest`.
         */
        fun nullableField(name: String): String? =
            jsonOrNull?.get(name)?.takeUnless { it.isNull }?.asString()

        /**
         * The elements of a JSON array field, e.g. `elements("articles")`.
         *
         * Here rather than at the call site because `JsonNode` carries its **own** `map`, which
         * Kotlin resolves in preference to the `Iterable` extension and which does something else
         * entirely — it maps the node, not its elements. The symptom is every element coming back as
         * the array itself, which fails as a null field somewhere later rather than as anything
         * legible. Also cost a run in `ArticleApiTest`.
         */
        fun elements(name: String): List<JsonNode> = jsonOrNull?.get(name)?.toList().orEmpty()

        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    /** No caller at all. See the class docblock: this actively clears, it does not merely omit. */
    fun anonymous(): RequestPostProcessor? = null

    fun get(path: String, caller: RequestPostProcessor? = null) = call(HttpMethod.GET, path, caller)
    fun delete(path: String, caller: RequestPostProcessor? = null) = call(HttpMethod.DELETE, path, caller)

    fun post(path: String, body: String? = null, caller: RequestPostProcessor? = null) =
        call(HttpMethod.POST, path, caller, body)

    fun put(path: String, body: String? = null, caller: RequestPostProcessor? = null) =
        call(HttpMethod.PUT, path, caller, body)

    /** A `multipart/form-data` upload, which is the only way to reach `POST /v2/files`. */
    fun upload(
        path: String,
        param: String,
        filename: String,
        contentType: String,
        content: ByteArray,
        caller: RequestPostProcessor? = null,
    ): Response {
        val request = MockMvcRequestBuilders.multipart(path)
            .file(MockMultipartFile(param, filename, contentType, content))
        if (caller == null) TestSecurityContextHolder.clearContext() else request.with(caller)
        return respond(mockMvc.perform(request).andReturn().response)
    }

    private fun call(
        method: HttpMethod,
        path: String,
        caller: RequestPostProcessor?,
        body: String? = null,
    ): Response {
        val request = MockMvcRequestBuilders.request(method, path)
            .also { req -> body?.let { req.contentType(MediaType.APPLICATION_JSON).content(it) } }
        if (caller == null) TestSecurityContextHolder.clearContext() else request.with(caller)
        return respond(mockMvc.perform(request).andReturn().response)
    }

    private fun respond(response: org.springframework.mock.web.MockHttpServletResponse) = Response(
        status = response.status,
        // getContentAsString() decodes with the response's own charset, which is what a client does.
        // For a JSON body that is the whole story; for a served *file* it is lossy, which is what
        // [Response.bytes] is for.
        body = response.contentAsString,
        bytes = response.contentAsByteArray,
        headers = response.headerNames.associateWith { name -> response.getHeaders(name).map { it.toString() } },
    )

    /** Convenience for building request bodies without escaping quotes by hand. */
    fun body(vararg pairs: Pair<String, Any?>): String = MAPPER.writeValueAsString(pairs.toMap())

    /** Convenience for building nested request bodies. */
    fun body(map: Map<String, Any?>): String = MAPPER.writeValueAsString(map)

    private companion object {
        val MAPPER = jacksonObjectMapper()
    }
}
