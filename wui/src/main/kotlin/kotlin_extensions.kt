val Boolean?.isNotNullAndTrue: Boolean
    get() = this ?: false

// TODO: .not?
val Boolean.negation: Boolean
    get() = this.xor(true)

fun String?.emptyToNull() = if (this.isNullOrEmpty()) null else this
fun String?.blankToNull() = if (this.isNullOrBlank()) null else this

fun String.truncate(n: Int) = if (this.length <= n) this else "${this.take(n - 3)}..."

fun Any?.ifExistsStr(str: () -> String) = if (this != null) str() else ""
fun Boolean.strIfTrue(str: () -> String) = if (this) str() else ""