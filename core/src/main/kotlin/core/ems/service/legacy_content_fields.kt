package core.ems.service

import core.exception.InvalidRequestException
import core.exception.ReqError

/**
 * Rejects content fields that used to work and no longer do (EZ-1730).
 *
 * EZ-1729 moved exercise text, course exercise instructions and article text to Markdown and
 * removed the `*_adoc` and `*_html` request fields. Spring Boot disables
 * `FAIL_ON_UNKNOWN_PROPERTIES`, so simply deleting them meant a client still sending `text_adoc`
 * got **200 OK and an empty exercise** — a silent, data-shaped failure, and the worst kind to
 * debug from the outside.
 *
 * The fields therefore survive in the request DTOs for one purpose: being named in a 400. They
 * are never read, never stored, and carry no validation annotations, since the request is
 * rejected before any of that would matter.
 *
 * Delete this file and the `legacy*` DTO properties once the external clients (ide-plugins,
 * easy-py, CLI) are known to be off them. Nothing in this repo sends them: the web app never has.
 */
fun rejectLegacyContentFields(replacement: String, vararg legacy: Pair<String, String?>) {
    val present = legacy.filter { (_, value) -> value != null }.map { (name, _) -> name }
    if (present.isEmpty()) return

    throw InvalidRequestException(
        "No longer supported: ${present.joinToString(", ")}. " +
                "Content is Markdown now — send '$replacement' instead (EZ-1729).",
        ReqError.INVALID_PARAMETER_VALUE,
        // One comma-joined attribute rather than one per field: `attrs` is serialised as a map,
        // so repeating the same key silently keeps only the last value — a client reading it
        // would have been told about one offending field when two were sent.
        "fields" to present.joinToString(","),
        "replacement" to replacement,
    )
}
