import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration


val JsonUtil = Json(JsonConfiguration.Stable)

fun <T> String?.parseTo(deserializer: DeserializationStrategy<T>): T? =
        if (this == null) null else JsonUtil.parse(deserializer, this)
