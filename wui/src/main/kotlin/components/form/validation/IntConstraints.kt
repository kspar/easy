package components.form.validation

import translation.Str

object IntConstraints {

    class MustBeInt : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String) = when {
            value.toIntOrNull() == null -> violation("$fieldNameForMessage ${Str.constraintMustBeInt}")
            else -> null
        }
    }

    class MinMax(
        private val min: Int = Int.MIN_VALUE,
        private val max: Int = Int.MAX_VALUE,
    ) : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String): ConstraintViolation<String>? {
            val int = value.toIntOrNull() ?: return null
            return when {
                int < min || int > max -> violation("$fieldNameForMessage ${Str.constraintMinMax} $min–$max")
                else -> null
            }
        }
    }
}