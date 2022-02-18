package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.ems.service.*
import core.util.DateTimeDeserializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class UpdateCourseExercise(private val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("replace") @field:Valid
        val replace: ReplaceReq?,
        @JsonProperty("delete") @field:Valid
        val delete: Set<DeleteFieldReq>?,
    )

    data class ReplaceReq(
        @JsonProperty("title_alias") @field:Size(max = 100)
        val titleAlias: String?,
        @JsonProperty("instructions_adoc") @field:Size(max = 300000)
        val instructionsAdoc: String?,
        @JsonProperty("threshold") @field:Min(0) @field:Max(100)
        val threshold: Int?,
        @JsonProperty("soft_deadline")
        @JsonDeserialize(using = DateTimeDeserializer::class)
        val softDeadline: DateTime?,
        @JsonProperty("hard_deadline")
        @JsonDeserialize(using = DateTimeDeserializer::class)
        val hardDeadline: DateTime?,
        @JsonProperty("assessments_student_visible")
        val assessmentsStudentVisible: Boolean?,
        // Functionality duplicated by two fields, so we don't have to assume that clients know the current time
        // isStudentVisible overrides studentVisibleFrom
        @JsonProperty("student_visible")
        val isStudentVisible: Boolean?,
        // null here is still interpreted as "do-not-change" rather than "hide"
        @JsonProperty("student_visible_from")
        @JsonDeserialize(using = DateTimeDeserializer::class)
        val studentVisibleFrom: DateTime?,
        @JsonProperty("moodle_exercise_id") @field:Size(max = 100)
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
        log.debug { "Update course exercise $courseExIdStr on course $courseIdStr by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(caller, courseId)
        assertCourseExerciseIsOnCourse(courseExId, courseId, false)

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