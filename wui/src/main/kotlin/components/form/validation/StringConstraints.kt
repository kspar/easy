package components.form.validation

object StringConstraints {
    class NotBlank(private val showMsg: Boolean) : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String) = when {
            value.isBlank() -> violation(if (showMsg) "$fieldNameForMessage on kohustuslik" else "")
            else -> null
        }
    }

    class Length(
        private val min: Int = 0,
        private val max: Int = Int.MAX_VALUE,
    ) : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String) = when {
            value.length < min -> violation("$fieldNameForMessage on liiga lühike, minimaalne pikkus on $min tähemärki")
            value.length > max -> violation("$fieldNameForMessage on liiga pikk, maksimaalne pikkus on $max tähemärki")
            else -> null
        }
    }
}