package components.form.validation

import EzDate

class DateTimeConstraints {

    object NonNullAndValid : FieldConstraint<EzDate?>() {
        override fun validate(value: EzDate?, fieldNameForMessage: String): ConstraintViolation<EzDate?>? = when {
            value == null -> violation("Kuupäev või kellaaeg on puudu või vigane")
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