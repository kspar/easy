package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.ems.service.AdocService
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeDeserializer
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.annotation.JsonDeserialize


@RestController
@RequestMapping("/v2")
class UpdateCourseExercise(private val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("replace") @field:Valid
        val replace: ReplaceReq?,
        @param:JsonProperty("delete") @field:Valid
        val delete: Set<DeleteFieldReq>?,
    )

    data class ReplaceReq(
        @param:JsonProperty("title_alias") @field:Size(max = 100)
        val titleAlias: String?,
        @param:JsonProperty("instructions_adoc") @field:Size(max = 300000)
        val instructionsAdoc: String?,
        @param:JsonProperty("threshold") @field:Min(0) @field:Max(100)
        val threshold: Int?,
        @param:JsonProperty("soft_deadline")
        @param:JsonDeserialize(using = DateTimeDeserializer::class)
        val softDeadline: DateTime?,
        @param:JsonProperty("hard_deadline")
        @param:JsonDeserialize(using = DateTimeDeserializer::class)
        val hardDeadline: DateTime?,
        @param:JsonProperty("assessments_student_visible")
        val assessmentsStudentVisible: Boolean?,
        // Functionality duplicated by two fields, so we don't have to assume that clients know the current time
        // isStudentVisible overrides studentVisibleFrom
        @param:JsonProperty("student_visible")
        val isStudentVisible: Boolean?,
        // null here is still interpreted as "do-not-change" rather than "hide"
        @param:JsonProperty("student_visible_from")
        @param:JsonDeserialize(using = DateTimeDeserializer::class)
        val studentVisibleFrom: DateTime?,
        @param:JsonProperty("moodle_exercise_id") @field:Size(max = 100)
        val moodleExerciseId: String?,
    )

    enum class DeleteFieldReq {
        TITLE_ALIAS,
        INSTRUCTIONS_ADOC,
        SOFT_DEADLINE,
        HARD_DEADLINE,
        MOODLE_EXERCISE_ID,
    }

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PatchMapping("/courses/{courseId}/exercises/{courseExerciseId}")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Update course exercise $courseExIdStr on course $courseIdStr by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        updateCourseExercise(courseExId, req)
    }

    private fun updateCourseExercise(courseExId: Long, update: Req) {
        val replace = update.replace
        val delete = update.delete
        val now = DateTime.now()

        transaction {

            CourseExercise.update({ CourseExercise.id eq courseExId }) {
                it[modifiedAt] = now

                if (replace?.titleAlias != null)
                    it[titleAlias] = replace.titleAlias
                if (replace?.instructionsAdoc != null) {
                    it[instructionsAdoc] = replace.instructionsAdoc
                    it[instructionsHtml] = adocService.adocToHtml(replace.instructionsAdoc)
                }
                if (replace?.threshold != null)
                    it[gradeThreshold] = replace.threshold
                if (replace?.softDeadline != null)
                    it[softDeadline] = replace.softDeadline
                if (replace?.hardDeadline != null)
                    it[hardDeadline] = replace.hardDeadline
                if (replace?.assessmentsStudentVisible != null)
                    it[assessmentsStudentVisible] = replace.assessmentsStudentVisible
                if (replace?.moodleExerciseId != null)
                    it[moodleExId] = replace.moodleExerciseId

                if (replace?.isStudentVisible != null || replace?.studentVisibleFrom != null) {
                    val studentVisibleFromTime = when {
                        replace.isStudentVisible == null -> replace.studentVisibleFrom
                        replace.isStudentVisible -> now
                        else -> null
                    }
                    it[studentVisibleFrom] = studentVisibleFromTime
                }

                delete?.forEach { deleteField ->
                    when (deleteField) {
                        DeleteFieldReq.TITLE_ALIAS ->
                            it[titleAlias] = null

                        DeleteFieldReq.INSTRUCTIONS_ADOC -> {
                            it[instructionsAdoc] = null
                            it[instructionsHtml] = null
                        }

                        DeleteFieldReq.SOFT_DEADLINE ->
                            it[softDeadline] = null

                        DeleteFieldReq.HARD_DEADLINE ->
                            it[hardDeadline] = null

                        DeleteFieldReq.MOODLE_EXERCISE_ID ->
                            it[moodleExId] = null
                    }
                }
            }
        }
    }
}