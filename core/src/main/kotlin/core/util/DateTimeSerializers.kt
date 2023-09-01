package core.util

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.ISODateTimeFormat


private val ENCODE_FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
private val DECODE_FORMATTER = ISODateTimeFormat.dateTimeParser().withZoneUTC()

class DateTimeSerializer : JsonSerializer<DateTime>() {
    override fun serialize(value: DateTime, gen: JsonGenerator, serializerProvider: SerializerProvider) {
        gen.writeString(ENCODE_FORMATTER.print(value))
    }
}

class DateTimeDeserializer : JsonDeserializer<DateTime>() {
    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext?): DateTime {
        return DECODE_FORMATTER.parseDateTime(parser.text)
    }
}