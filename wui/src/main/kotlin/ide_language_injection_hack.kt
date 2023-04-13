@file:Suppress("PackageDirectoryMismatch")

package org.intellij.lang.annotations

/**
 * Hack to enable IDEA automatic language injection for Kotlin/JS. Use as the JVM version.
 * Example:
 * fun renderMustache(@Language("handlebars") template: String)
 *
 * Issue: https://youtrack.jetbrains.com/issue/KTIJ-16340
 * Workaround stolen from: https://github.com/kotest/kotest/issues/2916
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.ANNOTATION_CLASS
)
annotation class Language(
    val value: String,
    val prefix: String = "",
    val suffix: String = ""
)
