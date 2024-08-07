package components.form.validation

import translation.Str

object StringConstraints {
    class NotBlank(private val showMsg: Boolean) : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String) = when {
            value.isBlank() -> violation(if (showMsg) "$fieldNameForMessage ${Str.constraintIsRequired}" else "")
            else -> null
        }
    }

    class Length(
        private val min: Int = 0,
        private val max: Int = Int.MAX_VALUE,
    ) : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String) = when {
            value.length < min -> violation("$fieldNameForMessage ${Str.constraintTooShort} $min ${if (min == 1) Str.characterSingular else Str.characterPlural}")
            value.length > max -> violation("$fieldNameForMessage ${Str.constraintTooLong} $max ${if (max == 1) Str.characterSingular else Str.characterPlural}")
            else -> null
        }
    }
}