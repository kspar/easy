package core.testing

import org.jetbrains.exposed.v1.core.Table
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

/**
 * Every Exposed [Table] object declared in `core.db`, found by reflection.
 *
 * Extracted from RichTextColumnsTest, which invented this and is still its best explanation:
 * reflecting over the table *objects* rather than reading `information_schema` means the check
 * needs no database and therefore runs on every push, instead of being tagged and skipped.
 *
 * Three things now build on it — the sweep's column guard, the between-test TRUNCATE, and the
 * schema-drift test — so it lives here rather than in any one of them.
 */
object ExposedTables {

    fun all(): List<Table> {
        val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
            // The default asks for a concrete, independent, @Component-annotated candidate. These
            // are plain Kotlin objects, so without this every table is filtered out and whatever
            // is built on top passes by finding nothing — the worst possible failure for a guard.
            override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition) = true
        }
        scanner.addIncludeFilter(AssignableTypeFilter(Table::class.java))

        val tables = scanner.findCandidateComponents("core.db")
            .mapNotNull { Class.forName(it.beanClassName).kotlin.objectInstance as? Table }

        // Same reasoning as the assertion inside RichTextColumnsTest, hoisted here so it protects
        // every caller: a scan that silently returns nothing looks exactly like a clean run.
        check(tables.size >= 30) {
            "Only ${tables.size} Exposed tables found in core.db — the scan is broken, not the schema."
        }
        return tables
    }
}
