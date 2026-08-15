package core.conf

import com.fasterxml.jackson.annotation.JsonProperty
import core.testing.EndpointInventory
import core.testing.IntegrationTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.bind.annotation.RequestBody
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KVisibility
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.kotlinFunction

/**
 * Every field name core puts on the wire is spelled deliberately.
 *
 * Half a contract test, for the price of reflection and no database. The web app's fixtures and
 * `web/src/api/types.ts` are written against these names, so a Kotlin property rename silently
 * turning `title_alias` into `titleAlias` is the "green suite, broken app" failure — the browser
 * suite would not notice, because it asserts against fixtures we wrote ourselves.
 *
 * Two rules, both from `doc/java-25-migration.md`:
 *
 * - **Every property carries `@JsonProperty`.** Without it the wire name is whatever Jackson infers
 *   from the Kotlin property name, which means a refactor renames a public API field. With it, the
 *   rename is a no-op and the wire name only changes when someone edits the string.
 * - **The name is `snake_case`.** The whole v2 API is, and one `camelCase` field is the kind of
 *   thing that gets worked around on the client rather than fixed.
 *
 * The annotation may sit on the getter (`@get:JsonProperty`, response DTOs) or on the constructor
 * parameter (`@param:JsonProperty`, request DTOs) — Jackson 3 + Kotlin needs the distinction and
 * this test accepts either, because which one is correct depends on direction rather than on style.
 *
 * The full shape descriptor this is a down payment on — nullability and types as well as names,
 * committed to git so the diff is the review — is EZ-1770.
 */
@IntegrationTest
class DtoWireNamesTest(@Autowired private val mapping: RequestMappingHandlerMapping) {

    private val snakeCase = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

    /**
     * The data classes Jackson actually touches.
     *
     * Reachability, not declaration. The first version of this test collected every data class
     * declared inside a controller, and immediately reported 40-odd "findings" that were nothing of
     * the sort: `ExportPersonalData.JsonFile` becomes a zip entry, `ReadDirController.DirAccess` is
     * an intermediate used to build a `Resp`. Neither is ever serialised, so neither has any wire
     * name to get wrong, and demanding `@JsonProperty` on them would be noise that teaches people
     * to add annotations without reading why.
     *
     * So: start from each handler's return type and its `@RequestBody` parameters, then walk
     * property types and generic arguments. What that reaches is, by definition, the wire.
     *
     * Note that `private` is not the filter — a private data class nested inside a public `Resp` is
     * still serialised, and would still be missed by a visibility check.
     */
    private fun dtoClasses(): List<KClass<*>> {
        val seen = mutableSetOf<KClass<*>>()

        fun visit(type: KType?) {
            val kClass = type?.classifier as? KClass<*> ?: return
            type.arguments.forEach { visit(it.type) }

            // Ours, by package. The boundary has to be somewhere or the walk wanders into the JDK
            // and Spring; "declared in core" is the honest line, and anything outside it is not a
            // DTO whose wire names we control anyway.
            if (kClass.qualifiedName?.startsWith("core.") != true) return

            // Enums go on the wire as their name, and `name`/`ordinal` are intrinsic to every one
            // of them — never annotated, never should be. Recursing into them produced ten
            // "findings" that were nothing but the language.
            if (kClass.java.isEnum) return

            if (!seen.add(kClass)) return

            // *Not* filtered to `isData`. A DTO written as a plain class is serialised identically
            // by Jackson, and the earlier version returned before recursing into one — so the next
            // person to write `class Resp(...)` instead of `data class Resp(...)` would have got
            // silence from a guard whose whole claim is coverage by construction. None exists
            // today, which is exactly when this costs nothing to close.
            checkedProperties(kClass).forEach { visit(it.second) }
        }

        EndpointInventory.all(mapping).map { it.handler }.distinctBy { it.method }.forEach { handler ->
            runCatching { handler.method.kotlinFunction }.getOrNull()?.let { fn ->
                visit(fn.returnType)
                // Request bodies only — which is what this always meant to do. Walking *every*
                // parameter reaches the instance parameter (the controller, and from there its
                // injected services) and `caller: EasyUser`, which is resolved from the security
                // context and never deserialised from JSON. The earlier version got away with it
                // only because filtering to `isData` happened to exclude both.
                fn.parameters
                    .filter { it.kind == KParameter.Kind.VALUE }
                    .filter { p -> p.annotations.any { it is RequestBody } }
                    .forEach { visit(it.type) }
            }
        }
        return seen.toList()
    }

    /**
     * The properties Jackson will serialise, as `name to type`.
     *
     * Every public member property, not just the primary constructor's — a `val` declared in the
     * class body is on the wire too, and the first version of this test looked only at constructor
     * parameters. Non-public ones are excluded because Jackson does not see them either.
     */
    private fun checkedProperties(dto: KClass<*>): List<Pair<String, KType>> =
        dto.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { it.name to it.returnType }

    private fun wireName(dto: KClass<*>, propertyName: String): String? {
        val fromGetter = dto.memberProperties
            .firstOrNull { it.name == propertyName }
            ?.javaGetter?.getAnnotation(JsonProperty::class.java)?.value
        if (fromGetter != null) return fromGetter

        return dto.primaryConstructor
            ?.parameters?.firstOrNull { it.name == propertyName }
            ?.annotations?.filterIsInstance<JsonProperty>()?.firstOrNull()?.value
    }

    @Test
    fun `the scan finds the DTOs`() {
        val found = dtoClasses()
        assertTrue(found.size >= 50) {
            "Only ${found.size} DTO classes found inside controllers — the scan is broken, and both " +
                    "tests below would then pass by checking nothing."
        }
    }

    @Test
    fun `every DTO property declares its wire name`() {
        val undeclared = dtoClasses().flatMap { dto ->
            checkedProperties(dto)
                .map { it.first }
                .filter { wireName(dto, it) == null }
                .map { "${dto.qualifiedName}.$it" }
        }.sorted()

        assertTrue(undeclared.isEmpty()) {
            "These DTO properties have no @JsonProperty, so their wire name is whatever Jackson " +
                    "infers from the Kotlin property name:\n" +
                    undeclared.joinToString("\n") { "  $it" } +
                    "\n\nThat makes an ordinary Kotlin rename a breaking API change nobody reviews. " +
                    "Add @get:JsonProperty(\"snake_name\") for a response DTO or " +
                    "@param:JsonProperty(\"snake_name\") for a request DTO — see doc/java-25-migration.md " +
                    "for why the target matters under Jackson 3."
        }
    }

    @Test
    fun `every wire name is snake_case`() {
        val wrong = dtoClasses().flatMap { dto ->
            checkedProperties(dto)
                .map { it.first }
                .mapNotNull { prop -> wireName(dto, prop)?.let { prop to it } }
                .filterNot { (_, wire) -> snakeCase.matches(wire) }
                .map { (prop, wire) -> "${dto.qualifiedName}.$prop -> \"$wire\"" }
        }.sorted()

        assertTrue(wrong.isEmpty()) {
            "These wire names are not snake_case:\n" +
                    wrong.joinToString("\n") { "  $it" } +
                    "\n\nThe whole v2 API is snake_case, and a single exception gets worked around on " +
                    "the client rather than fixed. Note that changing one is a breaking API change: " +
                    "check web/src/api/types.ts and the browser-suite fixtures before renaming."
        }
    }
}
