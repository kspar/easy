import kotlin.js.Date


val MONTHS = listOf("jaanuar", "veebruar", "märts", "aprill", "mai", "juuni",
        "juuli", "august", "september", "oktoober", "november", "detsember")

/**
 * Convert to Eastern European time.
 */
fun Date.toEet() =
        Date(this.toLocaleString("en-us", dateLocaleOptions { timeZone = "Europe/Tallinn" }))

fun nowTimestamp() = Date().toLocaleString("sv").replace(" ", "_")

/**
 * Return a datetime string in format '31. jaanuar 2019, 23.59' of this date in Estonian time zone.
 */
fun Date.toEstonianString(): String {
    val d = this
    val paddedHours = d.getHours().toString().padStart(2, '0')
    val paddedMins = d.getMinutes().toString().padStart(2, '0')
    return "${d.getDate()}. ${MONTHS[d.getMonth()]} ${d.getFullYear()}, $paddedHours:$paddedMins"
}

operator fun Date.compareTo(other: Date): Int =
        (this.getTime() - other.getTime()).toInt()

