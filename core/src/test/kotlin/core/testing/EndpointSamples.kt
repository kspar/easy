package core.testing

import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.jvm.kotlinFunction

/**
 * A request that will actually reach an endpoint's handler.
 *
 * [body] is null for endpoints taking no `@RequestBody`.
 */
data class Sample(
    val endpoint: Endpoint,
    val path: String,
    val body: String?,
    /**
     * Name of the `MultipartFile` request parameter, if the endpoint takes one.
     *
     * Multipart endpoints need a genuinely different request, not a JSON one. `POST /v2/files`
     * answered **500** to a JSON body — Spring could not resolve the missing file part, and that
     * happens during argument resolution, so `@Secured` never ran. It looked in the matrix exactly
     * like an endpoint failing to refuse a student, which is the opposite of what was happening.
     */
    val multipartFileParam: String? = null,
)

/**
 * How to call every endpoint, so that the authorization matrix can call all of them.
 *
 * Mostly derived rather than written down: paths come from the mapping's own template with the
 * variables filled in, and bodies from [JsonBodies] reflecting over the `@RequestBody` type. That
 * matters for the property this is supposed to have — a controller added next month is covered the
 * day it is added, rather than the day somebody remembers to enrol it.
 *
 * The registry below is therefore only for what derivation cannot get right, and every entry needs
 * a reason. Two kinds:
 *
 * - [bodyOverrides] — the generated body does not satisfy a constraint the generator cannot see
 *   (`@Pattern`, a cross-field rule, a string that has to parse as something).
 * - [excluded] — the endpoint must not be called at all, even with a bad id.
 *
 * ### Why nonexistent ids are enough
 *
 * `@Secured` is evaluated before the handler body runs, so a *negative* check needs no fixture: call
 * with a well-formed id that points at nothing and the wrong role, and the answer is 403 no matter
 * what is in the database. That is what makes covering 124 endpoints affordable rather than a
 * fixture-building exercise per endpoint.
 */
object EndpointSamples {

    /**
     * A path variable's value. Everything here is well-formed and points at nothing.
     *
     * `999999` for ids — it parses as a Long, so it gets past `idToLongOrInvalidReq`, and it exists
     * in no table. Names that are not ids get a string that is equally absent.
     */
    private fun pathValue(variable: String): String = when {
        variable.contains("invite", ignoreCase = true) -> "ZZZZZZ"
        variable.equals("key", ignoreCase = true) -> "nonexistent-key"
        variable.contains("filename", ignoreCase = true) -> "nonexistent.png"
        variable.contains("alias", ignoreCase = true) -> "nonexistent-alias"
        variable.contains("username", ignoreCase = true) -> "nonexistent-user"
        variable.contains("Id", ignoreCase = false) -> "999999"
        else -> "999999"
    }

    private val pathVariable = Regex("\\{([^}:]+)(?::[^}]*)?}")

    fun pathFor(endpoint: Endpoint): String =
        pathVariable.replace(endpoint.pattern) { pathValue(it.groupValues[1]) }

    /**
     * Endpoints the matrix must not call, with why.
     *
     * Keyed `METHOD /pattern`. Kept as small as it can be: an exclusion is an endpoint whose
     * authorization nothing checks, so each one is a hole, and the list is asserted to be
     * non-vacuous — an entry that no longer names a real endpoint fails the test.
     */
    val excluded = mapOf(
        // Spring Boot's own error dispatch target, not ours. Calling it directly is meaningless:
        // it renders whatever status the request already had.
        "ANY /error" to "Spring Boot's BasicErrorController — the ERROR dispatch target, holds nothing of ours",
    )

    /**
     * Bodies the generator cannot produce correctly, with why.
     *
     * Keyed `METHOD /pattern`. Prefer teaching [JsonBodies] a constraint over adding an entry here:
     * an override is per-endpoint and goes stale silently, whereas a rule in the generator keeps
     * working for the next endpoint that needs it.
     */
    val bodyOverrides = mapOf<String, String>()

    fun key(endpoint: Endpoint) = "${endpoint.method} ${endpoint.pattern}"

    /**
     * A callable request for [endpoint], or **null if one cannot be built** — the endpoint is
     * excluded, or it needs a `@RequestBody` this cannot generate.
     *
     * The second case is why this returns null at all. It used to return a `Sample` unconditionally
     * for anything not excluded, which made the matrix's "every endpoint has a callable sample"
     * assertion **impossible to fail**: the list it computed was the excluded set minus the excluded
     * set, i.e. always empty. An endpoint whose DTO gained an unrenderable field got a broken body,
     * a sample all the same, and full marks for coverage. Found in review, and it is the exact
     * failure mode the docblocks around here keep warning about.
     */
    fun sampleFor(endpoint: Endpoint): Sample? {
        if (key(endpoint) in excluded) return null

        val multipart = multipartParam(endpoint)
        if (multipart != null) return Sample(endpoint, pathFor(endpoint), body = null, multipartFileParam = multipart)

        bodyOverrides[key(endpoint)]?.let { return Sample(endpoint, pathFor(endpoint), it) }

        val bodyType = requestBodyType(endpoint)
            ?: return Sample(endpoint, pathFor(endpoint), body = null) // no body needed at all

        val body = JsonBodies.forType(bodyType) ?: return null
        return Sample(endpoint, pathFor(endpoint), body)
    }

    private fun multipartParam(endpoint: Endpoint): String? {
        val fn = runCatching { endpoint.handler.method.kotlinFunction }.getOrNull() ?: return null
        val param = fn.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .firstOrNull { p ->
                (p.type.classifier as? kotlin.reflect.KClass<*>)
                    ?.qualifiedName == "org.springframework.web.multipart.MultipartFile"
            } ?: return null
        // `@RequestParam` without an explicit name leaves `value` as "", not null — so `?: param.name`
        // never fires and the part gets an empty name, which cannot bind. That surfaces as a 500 and
        // the matrix reads it as "this endpoint failed to refuse the caller", i.e. a security
        // failure that is not one. The single multipart endpoint today names its param, so this is
        // latent rather than live.
        return param.annotations.filterIsInstance<RequestParam>().firstOrNull()?.value?.ifBlank { null }
            ?: param.name
    }

    private fun requestBodyType(endpoint: Endpoint): KType? {
        val fn = runCatching { endpoint.handler.method.kotlinFunction }.getOrNull() ?: return null
        return fn.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .firstOrNull { p -> p.annotations.any { it is RequestBody } }
            ?.type
    }
}
