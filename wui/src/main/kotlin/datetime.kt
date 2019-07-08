import kotlin.js.Date


val MONTHS = listOf("jaanuar", "veebruar", "märts", "aprill", "mai", "juuni",
        "juuli", "august", "september", "oktoober", "november", "detsember")

/**
 * Convert to Eastern European time.
 */
fun Date.toEet() =
        Date(this.toLocaleString("en-us", dateLocaleOptions { timeZone = "Europe/Tallinn" }))

/**
 * Return a datetime string in format '31. jaanuar 2019, 23.59' of this date in Estonian time zone.
 */
fun Date.toEstonianString(): String {
    val d = this.toEet()
    return "${d.getDate()}. ${MONTHS[d.getMonth()]} ${d.getFullYear()}, ${d.getHours()}.${d.getMinutes()}"
}
