import kotlinx.serialization.*
import kotlinx.serialization.internal.StringDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlin.js.Date


val JsonUtil = Json(JsonConfiguration.Stable)


fun <T> String.parseTo(deserializer: DeserializationStrategy<T>): T =
        JsonUtil.parse(deserializer, this)


@Serializer(forClass = Date::class)
object DateSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor =
            StringDescriptor.withName("Date")

    override fun serialize(encoder: Encoder, obj: Date) {
        // Probably just Date.toISOString()
        TODO("Not implemented")
    }

    override fun deserialize(decoder: Decoder): Date {
        return Date(Date.parse(decoder.decodeString()))
    }
}
