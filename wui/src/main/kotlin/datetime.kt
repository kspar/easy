import translation.Str
import kotlin.js.Date
import kotlin.math.roundToInt


data class EzDate(val date: Date) : Comparable<EzDate> {
    companion object {
        fun now() = EzDate(Date())
        fun epoch() = EzDate(Date(0))
        fun future() = EzDate(Date(32529329048000))
        fun nowDelta(deltaMs: Long) = EzDate(Date(Date.now() + deltaMs))
        fun nowDeltaDays(deltaDays: Int) = nowDelta(deltaDays * 24 * 60 * 60 * 1000L)
    }

    enum class Format {
        // dd.MM.yy
        SHORT,

        // dd. month / dd. month yyyy
        DATE,

        // dd. month, hh:mm / dd. month yyyy, hh:mm
        FULL,
    }

    // this is before other == this < other
    override fun compareTo(other: EzDate): Int =
        (this.date.getTime() - other.date.getTime()).roundToInt()

    override fun toString() = toHumanString(Format.FULL)

    fun isSoonerThanHours(hours: Int) =
        this < nowDelta(hours * 60 * 60 * 1000L)

    fun isOnSameMinute(other: EzDate): Boolean =
        isOnSameDate(other)
                && this.date.getHours() == other.date.getHours()
                && this.date.getMinutes() == other.date.getMinutes()

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
        val paddedHours = this.date.getHours().pad()
        val paddedMins = this.date.getMinutes().pad()

        // Today
        if (this.isOnSameDate(now))
            return "${Str.today} $paddedHours:$paddedMins"

        // Yesterday
        val yesterday = nowDelta(-86_400_000)  // -24 hrs
        if (this.isOnSameDate(yesterday))
            return "${Str.yesterday} $paddedHours:$paddedMins"

        // Tomorrow
        val tomorrow = nowDelta(86_400_000)
        if (this.isOnSameDate(tomorrow))
            return "${Str.tomorrow} $paddedHours:$paddedMins"

        // Later
        val day = this.date.getDate().toString()
        val monthName = Str.monthList[this.date.getMonth()]
        val year4digit = this.date.getFullYear().toString()
        return when (format) {
            Format.SHORT -> {
                val paddedDay = day.pad()
                val paddedMonth = (this.date.getMonth() + 1).pad()
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

    fun toDatetimeFieldString(): String =
        "${date.getFullYear()}-${(date.getMonth() + 1).pad()}-${date.getDate().pad()}" +
                "T${date.getHours().pad()}:${date.getMinutes().pad()}"

    fun toIsoString() = date.toISOString().split(".")[0] + 'Z'

    private fun Int.pad() = this.toString().pad()
    private fun String.pad() = this.padStart(2, '0')
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
    return "${d.getDate()}. ${Str.monthList[d.getMonth()]} ${d.getFullYear()}, $paddedHours:$paddedMins"
}

operator fun Date.compareTo(other: Date): Int =
    (this.getTime() - other.getTime()).toInt()

