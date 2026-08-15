package core.testing

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod

/**
 * Builds a minimal request body that Jackson will deserialise and `@Valid` will accept.
 *
 * ### Why this has to exist
 *
 * The authorization matrix wants to call every endpoint with the wrong role and see **403**. For a
 * GET that needs nothing. For the 57 endpoints taking a `@RequestBody` it needs a body that gets
 * *past argument resolution*, because Spring resolves and validates arguments **before** invoking
 * the controller method — and `@Secured` is method security, an AOP interceptor around that
 * invocation. So a body Jackson cannot read, or one `@Valid` rejects, produces **400 before the
 * role is ever considered**.
 *
 * That distinction is the whole point. A 400 does not mean the endpoint refused the caller; it
 * means the request never got far enough to find out. Counting it as a pass would be the
 * vacuous-pass failure `doc/testing.md` is about — the matrix would report "no endpoint is
 * reachable by the wrong role" while never having asked most of them.
 *
 * ### What it does not do
 *
 * It knows nothing about what the values *mean*. Ids point at nothing, strings say "x". That is
 * deliberate and sufficient: the matrix's assertions are all decided by the filter chain or by
 * `@Secured`, both of which run before a single field is read. Nothing here is a fixture, and any
 * test that needs the values to mean something wants Fixtures instead.
 *
 * Where a constraint is beyond it (`@Pattern`, a cross-field rule, an enum-shaped string), the
 * endpoint gets an explicit body in [EndpointSamples]. The matrix reports a 400 rather than
 * swallowing it, so those announce themselves rather than hiding.
 */
object JsonBodies {

    /**
     * A body for [type], or **null if one cannot be built** — an unknown field type, or a nested
     * DTO that is itself unrenderable.
     *
     * Returning null rather than emitting `"null"` is load-bearing. The earlier version always
     * produced *something*, which quietly made the matrix's "every endpoint has a callable sample"
     * assertion impossible to fail: an endpoint whose DTO gained a `UUID` field got a body of
     * `{"x": null}`, a sample was still constructed, and the guard reported full coverage while the
     * endpoint 400'd. A guard that cannot fail is worse than none, so failure has to be
     * representable.
     */
    fun forType(type: KType): String? = renderValue(type, emptyList())

    private fun renderValue(type: KType, annotations: List<Annotation>): String? {
        val kClass = type.classifier as? KClass<*> ?: return null

        return when {
            kClass == String::class -> "\"${string(annotations)}\""
            kClass == Boolean::class -> "false"
            kClass == Int::class || kClass == Long::class -> number(annotations).toString()
            kClass == Double::class || kClass == Float::class -> "1.0"
            kClass.java.isEnum -> "\"${kClass.java.enumConstants.first().let { (it as Enum<*>).name }}\""

            // Joda DateTime, and anything else serialised as an ISO string.
            kClass.qualifiedName == "org.joda.time.DateTime" -> "\"2026-01-01T09:00:00.000Z\""

            kClass == List::class || kClass == Set::class || kClass == Collection::class -> {
                val element = type.arguments.firstOrNull()?.type
                // Empty unless @NotEmpty demands otherwise — an empty list is the smallest thing
                // that gets past validation, and the matrix does not care what is in it.
                if (annotations.any { it is NotEmpty } && element != null)
                    renderValue(element, emptyList())?.let { "[$it]" }
                else "[]"
            }

            kClass == Map::class -> "{}"
            kClass.primaryConstructor != null -> forClass(kClass)
            else -> null
        }
    }

    fun forClass(kClass: KClass<*>): String? {
        val ctor = kClass.primaryConstructor ?: return "{}"

        val fields = mutableListOf<String>()
        for (param in ctor.parameters) {
            val annotations = annotationsOf(kClass, param)

            // Nullable fields are omitted rather than sent as null. Absent is the safer of the two:
            // a DTO may treat explicit null as "clear this" while absent means "leave alone", and
            // the matrix has no business asking for either.
            if (param.type.isMarkedNullable && annotations.none { it is NotNull }) continue

            val name = wireName(kClass, param) ?: continue
            // A required field we cannot render makes the whole body unusable — say so rather than
            // emitting a null the endpoint will reject with 400.
            val value = renderValue(param.type, annotations) ?: return null
            fields += "\"$name\": $value"
        }
        return "{${fields.joinToString(", ")}}"
    }

    /**
     * A constructor parameter's annotations, including the ones written `@field:`.
     *
     * Kotlin puts `@field:Size` on the backing field, not the parameter, so `param.annotations`
     * alone misses most of the validation in this codebase — which would produce bodies that
     * deserialise and then fail `@Valid`, i.e. exactly the 400 this class exists to avoid.
     */
    private fun annotationsOf(kClass: KClass<*>, param: KParameter): List<Annotation> {
        val fromField = kClass.memberProperties
            .firstOrNull { it.name == param.name }
            ?.javaField?.annotations?.toList().orEmpty()
        return param.annotations + fromField
    }

    private fun wireName(kClass: KClass<*>, param: KParameter): String? {
        param.annotations.filterIsInstance<JsonProperty>().firstOrNull()?.let { return it.value }
        kClass.memberProperties.firstOrNull { it.name == param.name }
            ?.getter?.javaMethod?.getAnnotation(JsonProperty::class.java)?.let { return it.value }
        return param.name
    }

    private fun string(annotations: List<Annotation>): String {
        val size = annotations.filterIsInstance<Size>().firstOrNull()
        val min = maxOf(size?.min ?: 0, if (annotations.any { it is NotBlank }) 1 else 0)
        val max = size?.max ?: Int.MAX_VALUE

        // Shortest string the constraints allow, and at least one character.
        //
        // The earlier version clamped to 1 whenever there was no `max`, which silently ignored
        // `@Size(min = 6)` — the generated body then failed validation, and the matrix reported it
        // as an endpoint needing a hand-written override when the generator was the thing at fault.
        return "x".repeat(maxOf(min, 1).coerceAtMost(max))
    }

    private fun number(annotations: List<Annotation>): Long {
        val min = annotations.filterIsInstance<Min>().firstOrNull()?.value ?: 1L
        val max = annotations.filterIsInstance<Max>().firstOrNull()?.value ?: Long.MAX_VALUE
        return minOf(maxOf(1L, min), max)
    }
}
