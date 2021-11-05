import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.js.Date


val JsonUtil = Json


fun <T> String.parseTo(deserializer: DeserializationStrategy<T>): T =
        JsonUtil.decodeFromString(deserializer, this)

fun <T> KSerializer<T>.stringify(obj: T): String = JsonUtil.encodeToString(this, obj)


object DateSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Date", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Date) {
        // Probably just Date.toISOString()
        TODO("Not implemented")
    }

    override fun deserialize(decoder: Decoder): Date {
        return Date(Date.parse(decoder.decodeString()))
    }
}
