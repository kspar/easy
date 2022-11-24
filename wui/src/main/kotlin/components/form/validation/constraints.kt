package components.form.validation

// fun interface doesn't work with violation fun that references itself
abstract class FieldConstraint<FieldValueType> {
    abstract fun validate(value: FieldValueType, fieldNameForMessage: String): ConstraintViolation<FieldValueType>?

    protected fun violation(msg: String) = ConstraintViolation(msg, this)
}

data class ConstraintViolation<T>(val message: String, val constraint: FieldConstraint<T>)
