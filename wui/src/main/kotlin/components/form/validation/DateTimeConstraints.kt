package components.form.validation

import EzDate
import translation.Str

class DateTimeConstraints {

    class NonNullAndValid(private val showMsg: Boolean) : FieldConstraint<EzDate?>() {
        override fun validate(value: EzDate?, fieldNameForMessage: String): ConstraintViolation<EzDate?>? = when {
            value == null -> violation(if (showMsg) Str.constraintValidDate else "")
            else -> null
        }
    }

    object NotInPast : FieldConstraint<EzDate?>() {
        override fun validate(
            value: EzDate?,
            fieldNameForMessage: String
        ): ConstraintViolation<EzDate?>? = when {
            value != null && value < EzDate.now() -> violation(Str.constraintNotInPast)
            else -> null
        }
    }

    object NotInFuture : FieldConstraint<EzDate?>() {
        override fun validate(
            value: EzDate?,
            fieldNameForMessage: String
        ): ConstraintViolation<EzDate?>? = when {
            value != null && value > EzDate.now() -> violation(Str.constraintNotInFuture)
            else -> null
        }
    }

    object InThisMillennium : FieldConstraint<EzDate?>() {
        override fun validate(
            value: EzDate?, fieldNameForMessage: String
        ): ConstraintViolation<EzDate?>? = when {
            value != null && value > EzDate.future() -> violation(Str.constraintInThisMillennium)
            else -> null
        }
    }
}