package core.conf

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.jdk.StringDeserializer
import tools.jackson.databind.deser.std.StdScalarDeserializer
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.type.LogicalType

/**
 * Every JSON string core reads is refused if it contains U+0000.
 *
 * PostgreSQL `text` cannot hold the NUL character at all — the insert fails with `invalid byte
 * sequence for encoding "UTF8": 0x00` — and every free-text column here is `text`. So a NUL in a
 * request body used to travel all the way to the insert, fail there as an `ExposedSQLException`, and
 * come back as a bare 500 with a system notification mail: a client's mistake, delivered to ops.
 * Found by pasting one into a solution while stress-testing the dev app; it reaches both submit
 * endpoints, exercise text and assets, feedback, inline comments, snippets — anything that stores what
 * it was sent.
 *
 * **Refused, not stripped.** Stripping would store something other than what the client sent, and
 * for a submission that means grading text the student never submitted. A NUL in source code is
 * never deliberate — Python itself rejects a source file containing one — so the honest answer is
 * a 400 that says which field, and `EasyExceptionHandler` turns [NulCharacterException] into
 * `TEXT_CONTAINS_NUL` with the field's wire name in `attrs.field`. That handler path never mails.
 *
 * **Here, once, rather than per endpoint.** A `@field:` constraint on each DTO would be one more
 * thing for every new endpoint to forget, and forgetting it is silent until someone pastes a NUL. At
 * deserialization it covers every request body there is, and Jackson already knows the path, so the
 * refusal names `assets[0].file_content` rather than "something in the body". Note that the escape
 * sequence written out as text — a solution containing the six characters `\u0000` — is not a NUL
 * and is accepted; only the decoded character is refused.
 *
 * The mapper this customizes is Boot's, which also reads the responses that `RestTemplateBuilder`
 * clients built from the injected builder receive (Moodle, the AI providers). A NUL in one of those
 * would equally have failed at the insert; it now fails earlier, at the read, as a
 * `RestClientException`. Clients built with `RestTemplateBuilder()` or `jacksonObjectMapper()` of
 * their own are untouched.
 */
@Configuration
class RejectNulCharacters {

    @Bean
    fun rejectNulInJsonStrings() = JsonMapperBuilderCustomizer { builder ->
        builder.addModule(SimpleModule("reject-nul").addDeserializer(String::class.java, NulRejectingStringDeserializer()))
    }
}

/**
 * Jackson's own `String` deserializer, then the check.
 *
 * Delegates rather than subclasses: Jackson recognises its stock `StringDeserializer` by the
 * `@JacksonStdImpl` on it and, for `List<String>` and `String[]`, bypasses a deserializer it
 * recognises in favour of reading the strings itself — which a subclass would risk being taken for.
 * A distinct class is never mistaken for the default, so the check runs inside collections too.
 */
class NulRejectingStringDeserializer : StdScalarDeserializer<String>(String::class.java) {

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String? {
        val text = StringDeserializer.instance.deserialize(p, ctxt)
        if (text != null && text.indexOf('\u0000') >= 0) throw NulCharacterException(p)
        return text
    }

    override fun logicalType(): LogicalType = LogicalType.Textual

    override fun getEmptyValue(ctxt: DeserializationContext): Any = ""
}

/**
 * A `MismatchedInputException` so that Jackson's bean deserializers prepend the property path as
 * the exception passes through them, the way they do for any other bad value — which is what
 * [field] reads.
 */
class NulCharacterException(p: JsonParser) :
    MismatchedInputException(p, "Text must not contain the NUL character (U+0000)", String::class.java) {

    /** The offending field by wire name, e.g. `solution` or `assets[0].file_content`. */
    val field: String
        get() = path.joinToString("") { ref -> ref.propertyName?.let { ".$it" } ?: "[${ref.index}]" }
            .removePrefix(".")
}
