package core.conf

import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Bean validation stops at the first violation.
 *
 * A `@field:Valid` list is validated element by element, and Hibernate Validator's default is to
 * collect every violation it finds. So one request carrying millions of blank ids — about 3M fit in
 * the 32 MB nginx admits — built a ConstraintViolation and a FieldError per element, and the handler
 * then logged and returned a message made of all of them: gigabytes, in the process that serves
 * everyone, before any controller's own cap could run. A `@Size` on the list does not prevent it,
 * because the container constraint and the cascade into its elements are both evaluated. Failing
 * fast bounds the work to one violation for every endpoint at once; TeacherCheckSimilarity and
 * SendCourseInvites both take such lists. The cost is that a request with several invalid fields is
 * told about the first one only.
 */
@Configuration
class ValidationConf {
    @Bean
    fun failFastValidation() = ValidationConfigurationCustomizer {
        it.addProperty("hibernate.validator.fail_fast", "true")
    }
}
