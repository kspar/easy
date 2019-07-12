package core.util

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat


private val FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

class DateTimeSerializer : JsonSerializer<DateTime>() {
    override fun serialize(value: DateTime, gen: JsonGenerator, serializerProvider: SerializerProvider) {
        gen.writeString(FORMATTER.print(value))
    }
}

class DateTimeDeserializer : JsonDeserializer<DateTime>() {
    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext?): DateTime {
        return FORMATTER.parseDateTime(parser.text)
    }
}