package core.testing

import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

/**
 * What Jackson sees when it looks at one of our DTOs.
 *
 * One definition, because three things now need the same answers — the wire-name guard, the request
 * body generator, and the shape descriptor — and "what is this field called on the wire" is exactly
 * the kind of subtle rule that goes wrong differently in each copy. Under Jackson 3 + Kotlin the
 * annotation may sit on the getter (`@get:JsonProperty`, response DTOs) or on the constructor
 * parameter (`@param:JsonProperty`, request DTOs); getting that wrong in one place and right in
 * another would mean two tools disagreeing about the same class.
 */
object DtoIntrospection {

    /** Properties Jackson will serialise: public members, in declaration order where possible. */
    fun properties(dto: KClass<*>): List<Property> {
        val ctorOrder = dto.primaryConstructor?.parameters?.mapNotNull { it.name } ?: emptyList()
        val members = dto.memberProperties.filter { it.visibility == KVisibility.PUBLIC }

        // Constructor order first, so a caller that wants source order gets it. Note the shape
        // generator deliberately re-sorts alphabetically: reordering constructor parameters is a
        // no-op refactor, and a committed file that churned on one would train people to skim its
        // diffs. Source order matters here for readers of this list, not for that file.
        return members
            .sortedBy { p -> ctorOrder.indexOf(p.name).let { if (it < 0) Int.MAX_VALUE else it } }
            .map { Property(it.name, wireName(dto, it.name) ?: it.name, it.returnType) }
    }

    data class Property(val kotlinName: String, val wireName: String, val type: KType)

    /**
     * The name this property is serialised under, or null if it carries no `@JsonProperty`.
     *
     * Null is meaningful: `DtoWireNamesTest` treats it as a defect, because without the annotation
     * the wire name is whatever Jackson infers from the Kotlin name, and an ordinary rename then
     * becomes a breaking API change nobody reviewed.
     */
    fun wireName(dto: KClass<*>, propertyName: String): String? {
        dto.memberProperties.firstOrNull { it.name == propertyName }
            ?.javaGetter?.getAnnotation(JsonProperty::class.java)?.let { return it.value }

        return dto.primaryConstructor
            ?.parameters?.firstOrNull { it.name == propertyName }
            ?.annotations?.filterIsInstance<JsonProperty>()?.firstOrNull()?.value
    }

    /**
     * A constructor parameter's annotations, including the ones written `@field:`.
     *
     * Kotlin puts `@field:Size` on the backing field rather than the parameter, so reading
     * `param.annotations` alone misses most of the validation in this codebase.
     */
    fun annotationsOf(dto: KClass<*>, propertyName: String): List<Annotation> {
        val fromParam = dto.primaryConstructor?.parameters
            ?.firstOrNull { it.name == propertyName }?.annotations.orEmpty()
        val fromField = dto.memberProperties.firstOrNull { it.name == propertyName }
            ?.javaField?.annotations?.toList().orEmpty()
        return fromParam + fromField
    }

    /** Ours, by package — the boundary beyond which a type is not a DTO whose shape we control. */
    fun isOurs(kClass: KClass<*>): Boolean = kClass.qualifiedName?.startsWith("core.") == true
}
