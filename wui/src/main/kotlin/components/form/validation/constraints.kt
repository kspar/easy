package components.form.validation

abstract class FieldConstraint<FieldValueType> {
    abstract fun validate(value: FieldValueType, fieldNameForMessage: String): ConstraintViolation<FieldValueType>?

    protected fun violation(msg: String) = ConstraintViolation(msg, this)
}

data class ConstraintViolation<T>(val message: String, val constraint: FieldConstraint<T>)
