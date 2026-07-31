package core.util


import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.ISODateTimeFormat
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer


private val ENCODE_FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
private val DECODE_FORMATTER = ISODateTimeFormat.dateTimeParser().withZoneUTC()

class DateTimeSerializer : ValueSerializer<DateTime>() {
    override fun serialize(value: DateTime, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeString(ENCODE_FORMATTER.print(value))
    }
}

class DateTimeDeserializer : ValueDeserializer<DateTime>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DateTime {
        return DECODE_FORMATTER.parseDateTime(p.getString())
    }
}