package components.form.validation

import EzDate

class DateTimeConstraints {

    class NonNullAndValid(private val showMsg: Boolean) : FieldConstraint<EzDate?>() {
        override fun validate(value: EzDate?, fieldNameForMessage: String): ConstraintViolation<EzDate?>? = when {
            value == null -> violation(if (showMsg) "Kuupäev või kellaaeg on puudu või vigane" else "")
            else -> null
        }
    }

    object NotInPast : FieldConstraint<EzDate?>() {
        override fun validate(
            value: EzDate?,
            fieldNameForMessage: String
        ): ConstraintViolation<EzDate?>? = when {
            value == null -> null
            value < EzDate.now() -> violation("Aeg ei tohi olla minevikus")
            else -> null
        }
    }

    object NotInFuture : FieldConstraint<EzDate?>() {
        override fun validate(
            value: EzDate?,
            fieldNameForMessage: String
        ): ConstraintViolation<EzDate?>? = when {
            value == null -> null
            value > EzDate.now() -> violation("Aeg ei tohi olla tulevikus")
            else -> null
        }
    }
}