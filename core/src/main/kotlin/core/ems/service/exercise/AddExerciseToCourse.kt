package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseExercise
import core.db.Exercise
import core.db.StoredFile
import core.ems.service.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeDeserializer
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddExerciseToCourseCont(private val adocService: AdocService) {

    data class Req(
        @JsonProperty("exercise_id") @field:Size(max = 100)
        val exerciseId: String,
        @JsonProperty("threshold") @field:Min(0) @field:Max(100)
        val threshold: Int,
        @JsonProperty("student_visible")
        val isStudentVisible: Boolean,
        @JsonDeserialize(using = DateTimeDeserializer::class)
        @JsonProperty("soft_deadline")
        val softDeadline: DateTime?,
        @JsonDeserialize(using = DateTimeDeserializer::class)
        @JsonProperty("hard_deadline")
        val hardDeadline: DateTime?,
        @JsonProperty("assessments_student_visible")
        val assStudentVisible: Boolean,
        @JsonProperty("instructions_adoc") @field:Size(max = 300000)
        val instructionsAdoc: String?,
        @JsonProperty("title_alias") @field:Size(max = 100)
        val titleAlias: String?
    )

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {
        log.debug { "Adding exercise ${body.exerciseId} to course $courseIdString by ${caller.id}" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val exerciseId = body.exerciseId.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertTeacherOrAdminHasAccessToExercise(caller, exerciseId)

        if (!isCoursePresent(courseId)) {
            throw InvalidRequestException("Course $courseId does not exist")
        }

        if (isExerciseOnCourse(exerciseId, courseId, false)) {
            throw InvalidRequestException("Exercise $exerciseId is already on course $courseId", ReqError.EXERCISE_ALREADY_ON_COURSE)
        }

        val id = when (body.instructionsAdoc) {
            null -> insertCourseExercise(courseId, body, null)
            else -> insertCourseExercise(courseId, body, adocService.adocToHtml(body.instructionsAdoc))
        }
        return Resp(id.toString())
    }

    private fun isCoursePresent(courseId: Long): Boolean {
        return transaction {
            Course.select {
                Course.id eq courseId
            }.count() > 0
        }
    }

    private fun insertCourseExercise(courseId: Long, body: Req, html: String?): Long =
        transaction {
            val exerciseId = body.exerciseId.idToLongOrInvalidReq()
            val now = DateTime.now()
            val orderIdxMaxColumn = CourseExercise.orderIdx.max()

            val currentMaxOrderIdx = CourseExercise
                .slice(orderIdxMaxColumn)
                .select {
                    CourseExercise.course eq courseId
                }
                .groupBy(CourseExercise.course)
                .map { it[orderIdxMaxColumn] }
                .singleOrNull()

            val orderIndex = (currentMaxOrderIdx ?: 0) + IDX_STEP

            val studentVisibleFromTime = if (body.isStudentVisible) DateTime.now() else null

            val id = CourseExercise.insertAndGetId {
                it[course] = EntityID(courseId, Course)
                it[exercise] = EntityID(exerciseId, Exercise)
                it[createdAt] = now
                it[modifiedAt] = now
                it[gradeThreshold] = body.threshold
                it[softDeadline] = body.softDeadline
                it[hardDeadline] = body.hardDeadline
                it[orderIdx] = orderIndex
                it[studentVisibleFrom] = studentVisibleFromTime
                it[assessmentsStudentVisible] = body.assStudentVisible
                it[instructionsHtml] = html
                it[instructionsAdoc] = body.instructionsAdoc
                it[titleAlias] = body.titleAlias
            }

            if (html != null) {
                val inUse = StoredFile.slice(StoredFile.id)
                    .select { StoredFile.usageConfirmed eq false }
                    .map { it[StoredFile.id].value }
                    .filter { html.contains(it) }

                StoredFile.update({ StoredFile.id inList inUse }) {
                    it[StoredFile.usageConfirmed] = true
                    it[StoredFile.exercise] = EntityID(exerciseId, Exercise)
                }
            }

            id.value
        }
}
