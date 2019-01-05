package ee.urgas.ems.util

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

class DateTimeSerializer : JsonSerializer<DateTime>() {
    companion object {
        private val FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss")
    }

    override fun serialize(value: DateTime, gen: JsonGenerator, serializerProvider: SerializerProvider) {
        gen.writeString(FORMATTER.print(value))
    }
}
