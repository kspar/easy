import kotlin.js.Date
import kotlin.math.roundToInt


val MONTHS = listOf(
    "jaanuar", "veebruar", "märts", "aprill", "mai", "juuni",
    "juuli", "august", "september", "oktoober", "november", "detsember"
)

data class EzDate(val date: Date) : Comparable<EzDate> {
    // this is before other - this < other
    override fun compareTo(other: EzDate): Int =
        (this.date.getTime() - other.date.getTime()).roundToInt()

    companion object {
        fun now() = EzDate(Date())
        fun epoch() = EzDate(Date(0))
        fun future() = EzDate(Date(32529329048000))
        fun nowDelta(deltaMs: Long) = EzDate(Date(Date.now() + deltaMs))
    }

    enum class Format {
        // dd.MM.yy
        SHORT,
        // dd. month / dd. month yyyy
        DATE,
        // dd. month, hh:mm / dd. month yyyy, hh:mm
        FULL,
    }

    fun isOnSameDate(other: EzDate): Boolean {
        val isSameDate = this.date.getDate() == other.date.getDate()
        val isSameMonth = this.date.getMonth() == other.date.getMonth()
        val isSameYear = this.date.getFullYear() == other.date.getFullYear()
        return isSameDate && isSameMonth && isSameYear
    }

    fun isOnSameYear(other: EzDate): Boolean =
        this.date.getFullYear() == other.date.getFullYear()

    fun toHumanString(format: Format): String {
        val now = now()
        val paddedHours = this.date.getHours().toString().padStart(2, '0')
        val paddedMins = this.date.getMinutes().toString().padStart(2, '0')

        // Today
        if (this.isOnSameDate(now))
            return "täna $paddedHours:$paddedMins"

        // Yesterday
        val yesterday = nowDelta(-86_400_000)  // -24 hrs
        if (this.isOnSameDate(yesterday))
            return "eile $paddedHours:$paddedMins"

        // Later
        val day = this.date.getDate().toString()
        val monthName = MONTHS[this.date.getMonth()]
        val year4digit = this.date.getFullYear().toString()
        return when (format) {
            Format.SHORT -> {
                val paddedDay = day.padStart(2, '0')
                val paddedMonth = (this.date.getMonth() + 1).toString().padStart(2, '0')
                val year2digit = year4digit.substring(2)
                "$paddedDay.$paddedMonth.$year2digit"
            }
            Format.DATE -> {
                if (this.isOnSameYear(now))
                    "$day. $monthName"
                else
                    "$day. $monthName $year4digit"
            }
            Format.FULL ->
                if (this.isOnSameYear(now))
                    "$day. $monthName, $paddedHours:$paddedMins"
                else
                    "$day. $monthName $year4digit, $paddedHours:$paddedMins"
        }
    }
}

fun EzDate.toEstonianString(): String = date.toEstonianString()

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

