
val Boolean?.isNotNullAndTrue: Boolean
    get() = this ?: false

val Boolean.negation: Boolean
    get() = this.xor(true)

fun String?.emptyToNull() = if (this.isNullOrEmpty()) null else this
